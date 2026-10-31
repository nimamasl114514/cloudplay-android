package com.cloudplay.app.stream

import java.net.URI

/**
 * HLS 播放表改写：把分片 / 子播放表 / 密钥 URI 全部换成经本代理的本地地址。
 *
 * 与 Python 版差异（增强项）：
 *  - 额外改写 `#EXT-X-KEY` / `#EXT-X-MAP` / `#EXT-X-MEDIA` 行里的 `URI="..."`，
 *    否则分片密钥请求会绕过代理导致 403（无 Cookie/Referer）——已加单测。
 *  - 保留上游扩展名（ffmpeg 系 demuxer 要求白名单后缀）。
 */
object M3u8Rewriter {

    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    fun rewrite(text: String, baseUrl: String, register: (String) -> String): String {
        val out = StringBuilder(text.length + 256)
        for (line in text.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty()) {
                out.append(line).append('\n')
                continue
            }
            if (s.startsWith("#")) {
                out.append(rewriteTagUri(line, baseUrl, register)).append('\n')
                continue
            }
            val abs = resolve(baseUrl, s)
            out.append(register(abs)).append('\n')
        }
        return out.toString()
    }

    private fun rewriteTagUri(line: String, baseUrl: String, register: (String) -> String): String {
        if (!line.contains("URI=\"")) return line
        return URI_ATTR.replace(line) { m ->
            val abs = resolve(baseUrl, m.groupValues[1])
            "URI=\"" + register(abs) + "\""
        }
    }

    fun resolve(base: String, ref: String): String {
        return try {
            URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    /** 分片后缀（保留上游扩展名，无扩展名默认 .ts）。 */
    fun suffixOf(url: String): String {
        val path = try {
            URI(url).path ?: ""
        } catch (_: Exception) {
            url.substringBefore('?')
        }
        val name = path.substringAfterLast('/')
        val ext = if ('.' in name) name.substringAfterLast('.') else ""
        return if (ext.length in 1..6 && ext.all { it.isLetterOrDigit() }) ".$ext" else ".ts"
    }
}
