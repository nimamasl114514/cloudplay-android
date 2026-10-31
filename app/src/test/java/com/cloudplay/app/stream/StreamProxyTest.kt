package com.cloudplay.app.stream

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class StreamProxyTest {

    private lateinit var upstream: HttpServer
    private lateinit var proxy: StreamProxy
    private val client = OkHttpClient()

    private val data = ByteArray(4096) { (it % 251).toByte() }

    @Before
    fun setUp() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        upstream.createContext("/file.bin") { ex -> serveRange(ex, data, "application/octet-stream") }

        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:1.0,\nseg0.ts\n#EXTINF:1.0,\nseg1.ts\n#EXT-X-ENDLIST\n"
        upstream.createContext("/play.m3u8") { ex ->
            val b = playlist.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        upstream.createContext("/seg0.ts") { ex -> serveRange(ex, data, "video/mp2t") }

        upstream.start()
        proxy = StreamProxy()
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        upstream.stop(0)
    }

    private fun serveRange(ex: HttpExchange, payload: ByteArray, ct: String) {
        val range = ex.requestHeaders.getFirst("Range")
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").split("-")
            val start = spec[0].toIntOrNull() ?: 0
            val end = if (spec.size > 1 && spec[1].isNotEmpty()) spec[1].toInt() else payload.size - 1
            val len = end - start + 1
            ex.responseHeaders.add("Content-Type", ct)
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
            ex.sendResponseHeaders(206, len.toLong())
            ex.responseBody.use { it.write(payload, start, len) }
        } else {
            ex.responseHeaders.add("Content-Type", ct)
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
    }

    private fun baseUrl(): String = "http://127.0.0.1:${upstream.address.port}"

    @Test
    fun fullGetReturnsExactBytes() {
        val local = proxy.register(baseUrl() + "/file.bin", mapOf("Referer" to "https://x/"))
        val resp = client.newCall(Request.Builder().url(local).build()).execute()
        resp.use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.bytes().contentEquals(data))
            assertEquals("bytes", it.header("Accept-Ranges"))
        }
    }

    @Test
    fun rangeGetReturns206() {
        val local = proxy.register(baseUrl() + "/file.bin", emptyMap())
        val resp = client.newCall(
            Request.Builder().url(local).header("Range", "bytes=0-1023").build()
        ).execute()
        resp.use {
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
        val resp = client.newCall(Request.Builder().url(local).head().build()).execute()
        resp.use {
            assertEquals(200, it.code)
            assertEquals("4096", it.header("Content-Length"))
        }
    }

    @Test
    fun m3u8IsRewrittenAndSegmentsPlayable() {
        val local = proxy.register(baseUrl() + "/play.m3u8", mapOf("Cookie" to "a=b"))
        val resp = client.newCall(Request.Builder().url(local).build()).execute()
        val text = resp.use { it.body!!.string() }
        assertTrue(text.contains("/s/"))
        assertTrue(text.contains(".ts"))
        // 取第一个分片地址，经代理应能拿到字节
        val seg = text.lineSequence().first { it.startsWith("/s/") }
        val segResp = client.newCall(
            Request.Builder().url("http://127.0.0.1:${proxy.port}$seg").build()
        ).execute()
        segResp.use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.bytes().contentEquals(data))
        }
    }

    @Test
    fun unknownTokenReturns410() {
        val resp = client.newCall(
            Request.Builder().url("http://127.0.0.1:${proxy.port}/s/deadbeef.ts").build()
        ).execute()
        resp.use { assertEquals(410, it.code) }
    }
}
