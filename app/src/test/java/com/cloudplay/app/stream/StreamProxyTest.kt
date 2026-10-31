package com.cloudplay.app.stream

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamProxyTest {

    private lateinit var upstream: MockWebServer
    private lateinit var proxy: StreamProxy
    private val client = OkHttpClient()
    private val data = ByteArray(4096) { (it % 251).toByte() }

    private val playlist =
        "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:1.0,\nseg0.ts\n#EXTINF:1.0,\nseg1.ts\n#EXT-X-ENDLIST\n"

    @Before
    fun setUp() {
        upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = (request.path ?: "").substringBefore('?')
                return when (path) {
                    "/file.bin" -> serveRange(request, data, "application/octet-stream")
                    "/seg0.ts" -> serveRange(request, data, "video/mp2t")
                    "/play.m3u8" -> MockResponse()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(playlist)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        upstream.start()
        proxy = StreamProxy()
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        upstream.shutdown()
    }

    private fun serveRange(req: RecordedRequest, payload: ByteArray, ct: String): MockResponse {
        val range = req.getHeader("Range")
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split("-")
            val start = spec[0].toIntOrNull() ?: 0
            val end = if (spec.size > 1 && spec[1].isNotEmpty()) spec[1].toInt() else payload.size - 1
            val slice = payload.copyOfRange(start, end + 1)
            return MockResponse().setResponseCode(206)
                .setHeader("Content-Type", ct)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                .setBody(Buffer().write(slice))
        }
        return MockResponse().setResponseCode(200)
            .setHeader("Content-Type", ct)
            .setHeader("Accept-Ranges", "bytes")
            .setBody(Buffer().write(payload))
    }

    private fun baseUrl(): String = upstream.url("/").toString().removeSuffix("/")

    @Test
    fun fullGetReturnsExactBytes() {
        val local = proxy.register(baseUrl() + "/file.bin", mapOf("Referer" to "https://x/"))
        client.newCall(Request.Builder().url(local).build()).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.bytes().contentEquals(data))
            assertEquals("bytes", it.header("Accept-Ranges"))
        }
    }

    @Test
    fun rangeGetReturns206() {
        val local = proxy.register(baseUrl() + "/file.bin", emptyMap())
        client.newCall(Request.Builder().url(local).header("Range", "bytes=0-1023").build())
            .execute().use {
                assertEquals(206, it.code)
                val body = it.body!!.bytes()
                assertEquals(1024, body.size)
                assertEquals("bytes 0-1023/4096", it.header("Content-Range"))
                assertTrue(body.contentEquals(data.copyOfRange(0, 1024)))
            }
    }

    @Test
    fun headReturnsLengthWithoutBody() {
        val local = proxy.register(baseUrl() + "/file.bin", emptyMap())
        client.newCall(Request.Builder().url(local).head().build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("4096", it.header("Content-Length"))
        }
    }

    @Test
    fun m3u8IsRewrittenAndSegmentsPlayable() {
        val local = proxy.register(baseUrl() + "/play.m3u8", mapOf("Cookie" to "a=b"))
        val text = client.newCall(Request.Builder().url(local).build()).execute()
            .use { it.body!!.string() }
        assertTrue(text.contains("/s/"))
        assertTrue(text.contains(".ts"))
        val seg = text.lineSequence().first { it.startsWith("/s/") }
        client.newCall(Request.Builder().url("http://127.0.0.1:${proxy.port}$seg").build())
            .execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.bytes().contentEquals(data))
            }
    }

    @Test
    fun unknownTokenReturns410() {
        client.newCall(
            Request.Builder().url("http://127.0.0.1:${proxy.port}/s/deadbeef.ts").build()
        ).execute().use { assertEquals(410, it.code) }
    }
}
