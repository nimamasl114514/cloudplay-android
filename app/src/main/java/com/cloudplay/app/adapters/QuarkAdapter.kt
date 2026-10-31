package com.cloudplay.app.adapters

import com.cloudplay.app.core.AdapterError
import com.cloudplay.app.core.CloudDiskAdapter
import com.cloudplay.app.core.CloudFile
import com.cloudplay.app.core.PlaySource
import com.cloudplay.app.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * 夸克网盘适配器（UC 网盘继承本类，仅覆写端点常量）—— Python 版 QuarkDriveAdapter 的 Kotlin 移植。
 *
 * 端点依据：JY-789（file/v2/play、trans resolution list、sharepage/save）+ JY-790（file/sort、file/download）。
 * 鉴权：网页版 Cookie（__pus / __puus），401 / code=31001 → needLogin。
 */
open class QuarkAdapter(credential: Map<String, String> = emptyMap()) : CloudDiskAdapter(credential) {

    override val name = "quark"
    override val displayName = "夸克网盘"
    override val rootId = "0"

    protected open val api = "https://drive-pc.quark.cn/1/clouddrive"
    protected open val playApi = "https://drive-m.quark.cn/1/clouddrive"
    protected open val pr = "ucpro"
    protected open val referer = "https://pan.quark.cn"
    protected open val ua = UA_QUARK

    protected open val playQuery: Map<String, String> = mapOf(
        "uc_param_str" to "dtmtutpcsnnnvebipfdnfrprfr",
        "fetch_identity" to "true"
    )
    protected open val saveEntry = "quark_mobile_group_player"
    protected val needLoginCodes = setOf("31001")

    protected var cookie: String = credential["cookie"].orEmpty()

    override fun needCredential(): List<String> =
        if (cookie.isBlank()) listOf("cookie") else emptyList()

    override fun credentialHint(): String =
        if (cookie.isBlank()) "需要：cookie（__pus=...; __puus=...，取自 pan.quark.cn 登录态）"
        else "Cookie 已就绪（__pus/__puus）"

    override suspend fun login() {
        if (cookie.isBlank()) throw AdapterError(name, "缺少 Cookie 凭据（__pus / __puus）", needLogin = true)
    }

    protected fun stripCookie(key: String): String =
        cookie.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("$key=") }
            .joinToString("; ")

    protected fun mergeCookies(headers: okhttp3.Headers) {
        for (raw in headers.values("Set-Cookie")) {
            val head = raw.substringBefore(";").trim()
            if ('=' !in head) continue
            val k = head.substringBefore("=").trim()
            val v = head.substringAfter("=", "").trim()
            if (k !in listOf("__pus", "__puus") || v.isEmpty()) continue
            val rest = stripCookie(k)
            cookie = if (rest.isEmpty()) "$k=$v" else "$k=$v; $rest"
        }
    }

    protected fun request(
        path: String,
        method: String = "POST",
        params: Map<String, Any?> = emptyMap(),
        jsonBody: JSONObject? = null,
        base: String? = null,
        cookieOverride: String? = null
    ): JSONObject {
        val q = LinkedHashMap<String, Any?>()
        q["pr"] = pr
        q["fr"] = "pc"
        q.putAll(params)
        val headers = mapOf(
            "Cookie" to (cookieOverride ?: cookie),
            "Referer" to referer,
            "User-Agent" to ua
        )
        val r = Http.request(method, (base ?: api) + path, headers, q, jsonBody)
        mergeCookies(r.headers)
        val j = r.json
            ?: throw AdapterError(name, "$path 返回非 JSON（HTTP ${r.code}）", raw = r.body.take(300))
        val status = if (j.has("status")) j.optInt("status") else 0
        val code = j.optString("code", "0")
        val bad = r.code >= 400 || status >= 400 ||
            (code.isNotBlank() && code != "0" && code != "null")
        if (bad) {
            throw AdapterError(
                name,
                "$path 失败：HTTP ${r.code} status=$status code=$code ${j.optString("message")}".trim(),
                needLogin = r.code == 401 || code in needLoginCodes,
                raw = j.toString()
            )
        }
        return j
    }

    override suspend fun listFiles(parentId: String?): List<CloudFile> {
        val pid = parentId ?: rootId
        val out = ArrayList<CloudFile>()
        var page = 1
        val size = 100
        while (true) {
            val j = request(
                "/file/sort", "GET",
                mapOf(
                    "pdir_fid" to pid, "_page" to page, "_size" to size,
                    "_fetch_total" to 1, "fetch_all_file" to 1,
                    "fetch_risk_file_name" to 1
                )
            )
            val list = j.optJSONObject("data")?.optJSONArray("list") ?: break
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                out.add(
                    CloudFile(
                        id = it.optString("fid"),
                        name = it.optString("file_name"),
                        isDir = !it.optBoolean("file", false),
                        size = it.optLong("size", 0),
                        parentId = pid,
                        extra = mapOf(
                            "category" to it.optString("category"),
                            "format_type" to it.optString("format_type"),
                            "duration" to it.optLong("duration", 0)
                        )
                    )
                )
            }
            val total = j.optJSONObject("metadata")?.optInt("_total", 0) ?: 0
            if (list.length() < size || (total > 0 && page * size >= total)) break
            page++
        }
        return out
    }

    override suspend fun getLink(file: CloudFile): PlaySource {
        val snap = cookie
        val body = JSONObject().put("fids", JSONArray().put(file.id))
        val j = request("/file/download", "POST", emptyMap(), body, cookieOverride = snap)
        val data = j.optJSONArray("data")
        val url = data?.optJSONObject(0)?.optString("download_url").orEmpty()
        if (url.isBlank()) {
            throw AdapterError(name, "file/download 未返回 download_url", raw = j.optString("message"))
        }
        return PlaySource(
            url = url,
            headers = mapOf("Cookie" to snap, "Referer" to referer, "User-Agent" to ua),
            expireAt = System.currentTimeMillis() / 1000 + 1800,
            fileName = file.name
        )
    }

    /** 流畅播：清晰度列表（play/project 优先，file/v2/play 兜底）。 */
    override suspend fun listResolutions(file: CloudFile): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        var data: JSONObject? = null
        val attempts = listOf(
            "/file/v2/play/project" to JSONObject()
                .put("fid", file.id)
                .put("resolutions", "low,normal,high,super,2k,4k")
                .put("supports", "fmp4_av,m3u8,dolby_vision"),
            "/file/v2/play" to JSONObject().put("fid", file.id)
        )
        for ((path, body) in attempts) {
            try {
                val j = request(path, "POST", playQuery, body, base = playApi)
                data = j.optJSONObject("data")
                if ((data?.optJSONArray("video_list")?.length() ?: 0) > 0) break
            } catch (_: AdapterError) {
                // 换下一个端点
            }
        }
        val list = data?.optJSONArray("video_list") ?: return out
        val serverCookie = data.optString("server_cookie").orEmpty()
        val defaultName = data.optString("default_resolution_name").orEmpty()
        for (i in 0 until list.length()) {
            val v = list.optJSONObject(i) ?: continue
            val vi = v.optJSONObject("video_info") ?: JSONObject()
            val w = if (v.has("width")) v.optInt("width") else vi.optInt("width", 0)
            val h = if (v.has("height")) v.optInt("height") else vi.optInt("height", 0)
            val name = v.optString("resolution")
                .ifBlank { vi.optString("resolution") }
                .ifBlank { if (w > 0 && h > 0) "${w}x$h" else "" }
            val url = v.optString("url").ifBlank { vi.optString("url") }
            val headers = mutableMapOf<String, Any?>("User-Agent" to ua, "Referer" to referer)
            if (serverCookie.isNotBlank()) headers["Cookie"] = serverCookie
            out.add(
                mapOf(
                    "name" to name,
                    "url" to url,
                    "status" to if (url.isBlank()) "unavailable" else "success",
                    "width" to w,
                    "height" to h,
                    "format" to v.optString("format").ifBlank { vi.optString("format") },
                    "headers" to headers,
                    "is_default" to (defaultName.isNotBlank() && name == defaultName)
                )
            )
        }
        return out
    }

    companion object {
        const val UA_QUARK =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
                "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }
}

/** UC 网盘：夸克协议族，差异仅端点常量。ut 下载令牌待移植（可凭据手填 ut）。 */
class UcAdapter(credential: Map<String, String> = emptyMap()) : QuarkAdapter(credential) {
    override val name = "uc"
    override val displayName = "UC网盘"
    override val api = "https://pc-api.uc.cn/1/clouddrive"
    override val playApi = "https://pc-api.uc.cn/1/clouddrive"
    override val pr = "UCBrowser"
    override val referer = "https://drive.uc.cn"
    override val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) uc-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
            "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    override val playQuery: Map<String, String> = emptyMap()

    private val ut: String = credential["ut"].orEmpty()

    override fun credentialHint(): String =
        if (cookie.isBlank()) "需要：cookie（drive.uc.cn 登录态）；可选 ut"
        else "Cookie 已就绪" + if (ut.isBlank()) "（ut 未填，下载可能 403）" else "（ut 已填）"

    override suspend fun getLink(file: CloudFile): PlaySource {
        val snap = cookie
        val body = JSONObject().put("fids", JSONArray().put(file.id))
        val params = if (ut.isNotBlank()) mapOf("ut" to ut) else emptyMap()
        val j = request("/file/download", "POST", params, body, cookieOverride = snap)
        val url = j.optJSONArray("data")?.optJSONObject(0)?.optString("download_url").orEmpty()
        if (url.isBlank()) throw AdapterError(name, "file/download 未返回 download_url")
        return PlaySource(
            url = url,
            headers = mapOf("Cookie" to snap, "Referer" to referer, "User-Agent" to ua),
            expireAt = System.currentTimeMillis() / 1000 + 1800,
            fileName = file.name
        )
    }
}
