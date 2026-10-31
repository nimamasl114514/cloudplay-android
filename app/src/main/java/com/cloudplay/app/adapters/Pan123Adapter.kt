package com.cloudplay.app.adapters

import android.util.Base64
import com.cloudplay.app.core.AdapterError
import com.cloudplay.app.core.CloudDiskAdapter
import com.cloudplay.app.core.CloudFile
import com.cloudplay.app.core.PlaySource
import com.cloudplay.app.net.Http
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.zip.CRC32

/**
 * 123 云盘适配器 —— Python 版 Pan123Adapter 的 Kotlin 移植。
 *
 * 签名：timeSign = crc32(mapped[yyyyMMddHHmm 每位数字经 SIGN_TABLE 映射])，
 * query = `timeSign=<ts>-<rand>-crc32("<ts>|<rand>|<path>|web|3|<timeSign>")`。
 */
class Pan123Adapter(credential: Map<String, String> = emptyMap()) : CloudDiskAdapter(credential) {

    override val name = "123"
    override val displayName = "123 云盘"
    override val rootId = "0"

    private var token = credential["token"].orEmpty()
    private val username = credential["username"].orEmpty()
    private val password = credential["password"].orEmpty()

    override fun needCredential(): List<String> =
        if (token.isNotBlank()) emptyList() else listOf("token 或 username+password")

    override suspend fun login() {
        if (token.isNotBlank()) return
        if (username.isBlank() || password.isBlank()) {
            throw AdapterError(name, "缺少 token 或 username/password", needLogin = true)
        }
        val body = JSONObject().put("passport", username).put("password", password).put("remember", true)
        val r = Http.postJson(
            "$LOGIN_API/user/sign_in", body,
            mapOf(
                "origin" to "https://yun.123pan.com",
                "referer" to "https://yun.123pan.com/",
                "platform" to "web",
                "app-version" to "3"
            )
        )
        val j = r.json ?: throw AdapterError(name, "登录返回非 JSON（HTTP ${r.code}）", raw = r.body.take(300))
        if (j.optInt("code", -1) != 200) {
            throw AdapterError(name, j.optString("message", "登录失败"), needLogin = true, raw = j.toString())
        }
        token = j.optJSONObject("data")?.optString("token").orEmpty()
    }

    private fun request(
        url: String,
        method: String,
        jsonBody: JSONObject? = null,
        params: Map<String, Any?> = emptyMap()
    ): JSONObject {
        loginBlocking()
        val path = try {
            URI(url).path
        } catch (_: Exception) {
            url
        }
        val full = url + (if ('?' in url) "&" else "?") + signQuery(path)
        val headers = mapOf(
            "origin" to "https://yun.123pan.com",
            "referer" to "https://yun.123pan.com/",
            "authorization" to "Bearer $token",
            "platform" to "web",
            "app-version" to "3",
            "user-agent" to UA_WEB
        )
        val r = Http.request(method, full, headers, params, jsonBody)
        val j = r.json ?: throw AdapterError(name, "$path 返回非 JSON（HTTP ${r.code}）", raw = r.body.take(300))
        if (j.optInt("code", -1) != 0) {
            val code = j.optInt("code", 0)
            if (code == 401) token = ""
            throw AdapterError(name, j.optString("message", "接口返回非 0"), needLogin = code == 401, raw = j.toString())
        }
        return j
    }

    private fun loginBlocking() {
        // suspend login 的同步包装：调用方已在 IO 线程；token 已存在时零开销
        if (token.isBlank() && username.isNotBlank() && password.isNotBlank()) {
            val body = JSONObject().put("passport", username).put("password", password).put("remember", true)
            val r = Http.postJson(
                "$LOGIN_API/user/sign_in", body,
                mapOf("origin" to "https://yun.123pan.com", "referer" to "https://yun.123pan.com/", "platform" to "web", "app-version" to "3")
            )
            val j = r.json ?: throw AdapterError(name, "登录返回非 JSON")
            if (j.optInt("code", -1) != 200) throw AdapterError(name, "登录失败", needLogin = true)
            token = j.optJSONObject("data")?.optString("token").orEmpty()
        }
        if (token.isBlank()) throw AdapterError(name, "缺少 token", needLogin = true)
    }

    override suspend fun listFiles(parentId: String?): List<CloudFile> {
        val out = ArrayList<CloudFile>()
        var page = 1
        while (true) {
            val j = request(
                API_FILE_LIST, "GET", null,
                mapOf(
                    "driveId" to 0, "limit" to 100, "next" to 0, "orderBy" to "file_name",
                    "orderDirection" to "asc", "parentFileId" to (parentId ?: rootId),
                    "trashed" to "false", "SearchData" to "", "Page" to page,
                    "OnlyLookAbnormalFile" to 0, "event" to "homeListFile", "operateType" to 4
                )
            )
            val d = j.optJSONObject("data") ?: break
            val list = d.optJSONArray("InfoList") ?: break
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                out.add(
                    CloudFile(
                        id = it.optString("FileId"),
                        name = it.optString("FileName"),
                        isDir = it.optInt("Type", 0) == 1,
                        size = it.optLong("Size", 0),
                        parentId = parentId ?: rootId,
                        extra = mapOf(
                            "etag" to it.optString("Etag"),
                            "s3key_flag" to it.optString("S3KeyFlag"),
                            "type" to it.optInt("Type", 0),
                            "category" to it.optInt("Category", 0)
                        )
                    )
                )
            }
            if (d.optInt("Next", 0) == 0) break
            page++
        }
        return out
    }

    override suspend fun getLink(file: CloudFile): PlaySource {
        val body = JSONObject()
            .put("driveId", 0)
            .put("etag", file.extra["etag"] ?: "")
            .put("fileId", file.id.toLongOrNull() ?: 0L)
            .put("fileName", file.name)
            .put("s3keyFlag", file.extra["s3key_flag"] ?: "")
            .put("size", file.size)
            .put("type", file.extra["type"] ?: 0)
        val j = request(API_DOWNLOAD_INFO, "POST", body)
        var dl = j.optJSONObject("data")?.optString("DownloadUrl").orEmpty()
        if (dl.isBlank()) throw AdapterError(name, "download_info 未返回 DownloadUrl", raw = j.toString())
        // DownloadUrl 可能带 params=<base64 真链>
        val params = try {
            URI(dl).rawQuery?.split("&")
                ?.firstOrNull { it.startsWith("params=") }
                ?.substringAfter("=")
        } catch (_: Exception) {
            null
        }
        if (!params.isNullOrBlank()) {
            try {
                val decoded = String(Base64.decode(params, Base64.URL_SAFE or Base64.NO_WRAP))
                if (decoded.startsWith("http")) dl = decoded
            } catch (_: Exception) {
                // 保留原链
            }
        }
        return PlaySource(
            url = dl,
            headers = mapOf("Referer" to "https://yun.123pan.com/"),
            expireAt = System.currentTimeMillis() / 1000 + 1800,
            fileName = file.name
        )
    }

    companion object {
        private const val SIGN_TABLE = "adefghlmyijnopkqrstubcvwsz"
        private const val MAIN_API = "https://yun.123pan.com/b/api"
        private const val LOGIN_API = "https://login.123pan.com/api"
        private const val API_FILE_LIST = "$MAIN_API/file/list/new"
        private const val API_DOWNLOAD_INFO = "$MAIN_API/file/download_info"
        private const val UA_WEB = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"

        fun crc32(s: String): String {
            val c = CRC32()
            c.update(s.toByteArray())
            return c.value.toString()
        }

        fun signQuery(path: String, os: String = "web", version: String = "3"): String {
            val rnd = (Random().nextDouble() * 1e7).toLong().toString()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val now = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())
            val mapped = now.map { SIGN_TABLE[it.digitToInt()] }.joinToString("")
            val timeSign = crc32(mapped)
            val data = "$ts|$rnd|$path|$os|$version|$timeSign"
            return "timeSign=$ts-$rnd-${crc32(data)}"
        }
    }
}
