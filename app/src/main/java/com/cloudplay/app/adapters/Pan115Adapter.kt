package com.cloudplay.app.adapters

import com.cloudplay.app.core.AdapterError
import com.cloudplay.app.core.CloudDiskAdapter
import com.cloudplay.app.core.CloudFile
import com.cloudplay.app.core.PlaySource
import com.cloudplay.app.net.Http
import org.json.JSONObject

/** 115 网盘适配器（Cookie: UID/CID/SEID/KID；直链 CDN 要求账号 Cookie）。 */
class Pan115Adapter(credential: Map<String, String> = emptyMap()) : CloudDiskAdapter(credential) {

    override val name = "115"
    override val displayName = "115 网盘"
    override val rootId = "0"

    private val cookie = credential["cookie"].orEmpty()
    private val ua = (credential["ua"] ?: "").ifBlank { UA_115 }

    override fun needCredential(): List<String> =
        if (cookie.isBlank()) listOf("cookie") else emptyList()

    override suspend fun login() {
        if (cookie.isBlank()) throw AdapterError(name, "缺少 cookie（含 UID/CID/SEID）", needLogin = true)
    }

    private fun get(url: String, params: Map<String, Any?> = emptyMap()): JSONObject {
        if (cookie.isBlank()) throw AdapterError(name, "缺少 cookie", needLogin = true)
        val r = Http.request(
            "GET", url,
            mapOf("Cookie" to cookie, "User-Agent" to ua, "Referer" to "https://115.com/"),
            params
        )
        val j = r.json ?: throw AdapterError(name, "$url 返回非 JSON（HTTP ${r.code}）", raw = r.body.take(300))
        if (j.optBoolean("state", true).not() || (j.has("errno") && j.optInt("errno", 0) != 0)) {
            val errno = j.optString("errno")
            throw AdapterError(
                name, j.optString("error").ifBlank { j.optString("message", errno) },
                needLogin = errno in setOf("990001", "40101017", "40101018"),
                raw = j.toString()
            )
        }
        return j
    }

    override suspend fun quota(): Map<String, Any?> = try {
        val j = get(
            "$WEBAPI/files",
            mapOf("aid" to 1, "cid" to 0, "o" to "user_ptime", "asc" to 0, "offset" to 0, "show_dir" to 1, "limit" to 1)
        )
        val d = j.optJSONObject("data") ?: JSONObject()
        mapOf("count" to d.optInt("count"), "size" to d.optLong("size"))
    } catch (e: AdapterError) {
        mapOf("error" to e.message)
    }

    override suspend fun listFiles(parentId: String?): List<CloudFile> {
        val cid = parentId ?: rootId
        val out = ArrayList<CloudFile>()
        var offset = 0
        while (true) {
            val j = get(
                "$WEBAPI/files",
                mapOf(
                    "aid" to 1, "cid" to cid, "o" to "user_ptime", "asc" to 0,
                    "offset" to offset, "show_dir" to 1, "limit" to 200, "type" to 0,
                    "natsort" to 0, "source" to "", "format" to "json"
                )
            )
            val arr = j.optJSONArray("data") ?: break
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val fid = if (it.isNull("fid")) "" else it.optString("fid")
                val pick = it.optString("pc")
                out.add(
                    CloudFile(
                        id = it.optString("cid").ifBlank { fid.ifBlank { pick } },
                        name = it.optString("n"),
                        isDir = fid.isBlank(),
                        size = it.optLong("s", 0),
                        parentId = cid.toString(),
                        extra = mapOf(
                            "pick_code" to pick,
                            "sha1" to it.optString("sha"),
                            "cid" to it.optString("cid")
                        )
                    )
                )
            }
            if (arr.length() < 200) break
            offset += 200
        }
        return out
    }

    override suspend fun getLink(file: CloudFile): PlaySource {
        val pick = (file.extra["pick_code"] as? String).orEmpty()
        if (pick.isBlank()) throw AdapterError(name, "缺少 pick_code（文件 ${file.name}），请重新列目录")
        val r = Http.request(
            "GET", "$WEBAPI/files/download",
            mapOf("Cookie" to cookie, "User-Agent" to ua, "Referer" to "https://115.com/"),
            mapOf("pickcode" to pick, "dl" to 1, "type" to 4)
        )
        val j = r.json
        var url = j?.optString("file_url")?.ifBlank { j.optString("url") } ?: ""
        if (url.isBlank()) {
            Regex("https?://[^\"'\\s]+").find(r.body)?.let { url = it.value }
        }
        if (url.isBlank()) throw AdapterError(name, j?.optString("error") ?: "未拿到 file_url", raw = r.body.take(300))
        return PlaySource(
            url = url,
            headers = mapOf("Cookie" to cookie, "User-Agent" to ua, "Referer" to "https://115.com/"),
            expireAt = System.currentTimeMillis() / 1000 + 1800,
            fileName = file.name
        )
    }

    companion object {
        private const val WEBAPI = "https://webapi.115.com"
        private const val UA_115 = "Mozilla/5.0 115Browser/36.0.1"
    }
}

