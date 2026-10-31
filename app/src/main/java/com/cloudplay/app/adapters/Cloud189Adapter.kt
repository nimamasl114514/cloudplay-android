package com.cloudplay.app.adapters

import com.cloudplay.app.core.AdapterError
import com.cloudplay.app.core.CloudDiskAdapter
import com.cloudplay.app.core.CloudFile
import com.cloudplay.app.core.PlaySource
import com.cloudplay.app.net.Http
import org.json.JSONObject

/**
 * 天翼云盘适配器（Cookie 网页版）。
 * 取直链关键：getFileInfo.action → fileDownloadUrl → 请求该地址且**禁止自动跳转**，
 * 取 302 Location（最多两层），否则拿到的是最终体而不是带签名的 URL。
 */
class Cloud189Adapter(credential: Map<String, String> = emptyMap()) : CloudDiskAdapter(credential) {

    override val name = "189"
    override val displayName = "天翼云盘"
    override val rootId = "-11"

    private val cookie = credential["cookie"].orEmpty()
    private val ua = credential["ua"].orEmpty().ifBlank { DEFAULT_UA }

    override fun needCredential(): List<String> =
        if (cookie.isBlank()) listOf("cookie") else emptyList()

    override suspend fun login() {
        if (cookie.isBlank()) throw AdapterError(name, "缺少 cookie（网页端登录后复制）", needLogin = true)
    }

    private fun request(
        url: String,
        method: String = "GET",
        params: Map<String, Any?> = emptyMap(),
        jsonBody: JSONObject? = null
    ): JSONObject {
        if (cookie.isBlank()) throw AdapterError(name, "缺少 cookie", needLogin = true)
        val p = LinkedHashMap<String, Any?>()
        p["noCache"] = (Math.random() * 1e6).toLong()
        p.putAll(params)
        val r = Http.request(
            method, url,
            mapOf(
                "Cookie" to cookie,
                "User-Agent" to ua,
                "Accept" to "application/json;charset=UTF-8",
                "Referer" to "https://cloud.189.cn/web/main/"
            ),
            p, jsonBody
        )
        val j = r.json ?: throw AdapterError(name, "$url 返回非 JSON（HTTP ${r.code}）", raw = r.body.take(300))
        val code = j.optString("res_code").ifBlank { j.optString("errorCode") }
        if (code.isNotBlank() && code !in setOf("0", "Success", "None")) {
            throw AdapterError(
                name, j.optString("res_message").ifBlank { j.optString("errorMsg", code) },
                needLogin = code in setOf("-1", "11001", "UserAccountNotExist"),
                raw = j.toString()
            )
        }
        return j
    }

    override suspend fun listFiles(parentId: String?): List<CloudFile> {
        val fid = parentId ?: rootId
        val j = request(
            "$API/open/file/listFiles.action",
            params = mapOf(
                "albumId" to "", "fileId" to fid, "iconOption" to 5, "mediaAttr" to 0,
                "pageNum" to 1, "pageSize" to 200, "recursive" to 0, "sessionKey" to "",
                "slide" to 0, "sortType" to "lastOpTime", "descending" to "true"
            )
        )
        val out = ArrayList<CloudFile>()
        val list = j.optJSONObject("fileListAO")?.optJSONArray("fileList") ?: return out
        for (i in 0 until list.length()) {
            val it = list.optJSONObject(i) ?: continue
            out.add(
                CloudFile(
                    id = it.optString("id"),
                    name = it.optString("name"),
                    isDir = it.optBoolean("folder", false),
                    size = it.optLong("size", 0),
                    parentId = fid,
                    extra = mapOf(
                        "oss_media_url" to (it.optJSONObject("icon")?.optString("ossMediaURL") ?: "")
                    )
                )
            )
        }
        return out
    }

    override suspend fun getLink(file: CloudFile): PlaySource {
        val j = request("$API/portal/getFileInfo.action", params = mapOf("fileId" to file.id))
        var dl = j.optString("fileDownloadUrl")
        if (dl.isBlank()) throw AdapterError(name, "getFileInfo 未返回 fileDownloadUrl", raw = j.toString())
        if (dl.startsWith("//")) dl = "https:$dl" else if (dl.startsWith("/")) dl = "https://cloud.189.cn$dl"

        var final = dl
        runCatching {
            var r = Http.request("GET", dl, mapOf("User-Agent" to ua), emptyMap(), null, followRedirects = false)
            repeat(2) {
                if (r.code in listOf(301, 302, 303, 307, 308)) {
                    val loc = r.headers["location"] ?: return@runCatching
                    final = loc
                    r = Http.request("GET", loc, mapOf("User-Agent" to ua), emptyMap(), null, followRedirects = false)
                } else {
                    return@runCatching
                }
            }
        }
        final = final.replaceFirst("http://", "https://")
        return PlaySource(final, emptyMap(), System.currentTimeMillis() / 1000 + 1800, file.name)
    }

    companion object {
        private const val API = "https://cloud.189.cn/api"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
