package com.cloudplay.app.stream

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 本地流代理 —— 云播解耦关键件（Python 版 StreamProxy 的 Kotlin 等价实现）。
 *
 * 把「直链 + 必需请求头」包装成 http://127.0.0.1:<port>/s/<token>[.ext]，
 * Range 原样转发上游并回传 206 + Content-Range，播放器无需感知 Referer/Cookie。
 *
 * 支持：GET / HEAD、Range（含 `bytes=-N` 尾段）、m3u8 分片改写（含 KEY/MAP URI）、
 * 大文件流式转发（256 KiB 块）、CORS、上游错误透传。
 */
class StreamProxy(
    private val client: OkHttpClient = defaultClient()
) {

    data class Entry(val url: String, val headers: Map<String, String>, val name: String = "")

    private val registry = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "stream-proxy").apply { isDaemon = true }
    }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var running = false

    fun start(): Int {
        if (running) return port
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        running = true
        Thread({ acceptLoop(ss) }, "stream-proxy-accept").apply { isDaemon = true }.start()
        return port
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        registry.clear()
    }

    /** 注册直链，返回本地播放地址。 */
    fun register(url: String, headers: Map<String, String>, name: String = ""): String {
        check(port > 0) { "StreamProxy 未启动" }
        val token = md5(url).substring(0, 16)
        registry[token] = Entry(url, headers, name)
        return "http://127.0.0.1:$port/s/$token" + M3u8Rewriter.suffixOf(url)
    }

    fun revoke(localUrl: String) {
        val token = localUrl.substringAfter("/s/", "").substringBefore('.').substringBefore('?')
        if (token.isNotEmpty()) registry.remove(token)
    }

    fun size(): Int = registry.size

    // ------------------------------------------------------------------ server

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock = try {
                ss.accept()
            } catch (_: Exception) {
                if (!running) break else continue
            }
            runCatching { pool.execute { handle(sock) } }
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 20_000
                val input = s.getInputStream()
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                if (lines.isEmpty()) return
                val parts = lines[0].split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]
                val headers = HashMap<String, String>()
                for (line in lines.drop(1)) {
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] =
                        line.substring(i + 1).trim()
                }
                if (method != "GET" && method != "HEAD") {
                    respond(s.getOutputStream(), 405, emptyMap(), null)
                    return
                }
                val token = tokenOf(path)
                val entry = registry[token]
                if (entry == null) {
                    respond(s.getOutputStream(), 410, emptyMap(), null)
                    return
                }
                relay(s.getOutputStream(), method, headers["range"], entry)
            } catch (_: Exception) {
                runCatching { respond(s.getOutputStream(), 502, emptyMap(), null) }
            }
        }
    }

    private fun tokenOf(path: String): String {
        // /s/<token>[.ext][?query] -> token
        var p = path.substringBefore('?')
        if (p.startsWith("/s/")) p = p.removePrefix("/s/")
        val dot = p.indexOf('.')
        return if (dot >= 0) p.substring(0, dot) else p
    }

    private fun relay(out: OutputStream, method: String, range: String?, entry: Entry) {
        val rb = Request.Builder().url(entry.url).header("Accept-Encoding", "identity")
        entry.headers.forEach { (k, v) -> rb.header(k, v) }
        if (!range.isNullOrBlank()) rb.header("Range", range)
        if (method == "HEAD") rb.head() else rb.get()

        client.newCall(rb.build()).execute().use { resp ->
            val body = resp.body
            val ct = resp.header("Content-Type") ?: ""
            val isPlaylist = method == "GET" && (
                ct.lowercase().contains("mpegurl") ||
                    entry.url.substringBefore('?').lowercase().endsWith(".m3u8")
                )

            if (isPlaylist && body != null) {
                val text = body.string()
                val base = resp.request.url.toString()
                val rewritten = M3u8Rewriter.rewrite(text, base) { abs ->
                    val tok = md5(abs).substring(0, 16)
                    registry[tok] = Entry(abs, entry.headers, entry.name)
                    "/s/" + tok + M3u8Rewriter.suffixOf(abs)
                }
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                val h = mutableMapOf(
                    "Content-Type" to (resp.header("Content-Type")
                        ?: "application/vnd.apple.mpegurl"),
                    "Content-Length" to bytes.size.toString(),
                    "Accept-Ranges" to "bytes"
                )
                respond(out, resp.code, h, if (method == "GET") bytes else null)
                return
            }

            val h = LinkedHashMap<String, String>()
            for (k in listOf(
                "Content-Type", "Content-Length", "Content-Range",
                "Accept-Ranges", "Last-Modified", "ETag"
            )) {
                resp.header(k)?.let { h[k] = it }
            }
            if (!h.containsKey("Accept-Ranges")) h["Accept-Ranges"] = "bytes"
            if (method == "HEAD") {
                respond(out, resp.code, h, null)
                return
            }
            writeHead(out, resp.code, h)
            if (body != null) {
                body.byteStream().use { ins ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ http io

    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder(1024)
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1
        var b: Int
        while (true) {
            b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            buf.append(b.toChar())
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                return buf.toString().substring(0, buf.length - 4)
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
            if (buf.length > 16 * 1024) return null
        }
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        headers: Map<String, String>,
        body: ByteArray?
    ) {
        val h = LinkedHashMap(headers)
        if (body != null) h["Content-Length"] = body.size.toString()
        else if (!h.containsKey("Content-Length")) h["Content-Length"] = "0"
        writeHead(out, code, h)
        if (body != null) out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        302 -> "Found"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        410 -> "Gone"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "Status"
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun md5(s: String): String {
            val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
