package com.cloudplay.app.adapters

import com.cloudplay.app.core.AdapterError
import com.cloudplay.app.core.CloudDiskAdapter
import com.cloudplay.app.core.CloudFile
import com.cloudplay.app.core.PlaySource
import com.cloudplay.app.net.Http
import org.json.JSONObject

/**
 * 阿里云盘适配器 —— Python 版 AliyunDriveAdapter 的 Kotlin 移植。
 *
 * 两种模式：
 *  - personal（网页 token）：/v2/*，必须带 origin/Referer/X-Canary；Authorization 用 `Bearer\t`（tab）
 *  - openapi（开放平台）：/adrive/v1.0/*，标准 Bearer
 * 直链带 `x-oss-additional-headers=referer`，Referer 必须逐字 `https://www.aliyundrive.com/`，否则 403。
 */
class AliyunAdapter(credential: Map<String, String> = emptyMap()) : CloudDiskAdapter(credential) {

    override val name = "aliyun"
    override val displayName = "阿里云盘"
    override val rootId = "root"

    private var accessToken = credential["access_token"].orEmpty()
    private val refreshToken = credential["refresh_token"].orEmpty()
    private val deviceId = credential["device_id"].orEmpty()
    private var driveId = credential["drive_id"] ?: credential["default_drive_id"] ?: ""

    private val mode: String = credential["mode"].orEmpty().ifBlank {
        if (!credential["client_id"].isNullOrBlank() && credential["default_drive_id"].isNullOrBlank())
            "openapi" else "personal"
    }

    private val base: String =
        if (mode == "openapi") "https://openapi.alipan.com" else "https://api.alipan.com"

    private var expireAt = 0L

    override fun needCredential(): List<String> =
        if (accessToken.isNotBlank()) emptyList() else listOf("refresh_token")

    override fun credentialHint(): String =
        "模式=$mode；凭据：refresh_token 或 access_token" +
            if (accessToken.isNotBlank()) "（access_token 已就绪）" else ""

    override suspend fun login() {
        val now = System.currentTimeMillis() / 1000
        if (accessToken.isNotBlank() && now < expireAt - 60) return
        if (refreshToken.isBlank() && accessToken.isBlank()) {
            throw AdapterError(name, "缺少 refresh_token / access_token", needLogin = true)
        }
        if (refreshToken.isBlank()) return

        val r = Http.postJson(
            "https://auth.aliyundrive.com/v2/account/token",
            JSONObject().put("grant_type", "refresh_token").put("refresh_token", refreshToken)
        )
        val j = r.json ?: throw AdapterError(name, "token 刷新返回非 JSON（HTTP ${r.code}）", raw = r.body.take(400))
        val at = j.optString("access_token")
        if (at.isBlank()) {
            throw AdapterError(name, j.optString("message", "refresh_token 无效"), needLogin = true, raw = j.toString())
        }
        accessToken = at
        if (driveId.isBlank()) driveId = j.optString("default_drive_id")
        expireAt = now + j.optLong("expires_in", 7200)
    }

    private fun headers(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Authorization"] = "Bearer\t$accessToken"
        h["content-type"] = "application/json"
        h["x-request-id"] = "cloudplay-${System.currentTimeMillis()}"
        if (mode != "openapi") {
            h["origin"] = "https://www.alipan.com"
            h["Referer"] = "https://alipan.com/"
            h["X-Canary"] = "client=Android,app=adrive,version=v4.1.0"
            if (deviceId.isNotBlank()) h["X-Device-Id"] = deviceId
        }
        return h
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val r = Http.request("POST", base + path, headers(), emptyMap(), body)
        val j = r.json ?: throw AdapterError(name, "$path 返回非 JSON（HTTP ${r.code}）", raw = r.body.take(400))
        val code = j.optString("code")
        if (code.isNotBlank() && code != "0" && code != "Success") {
            throw AdapterError(
                name, j.optString("message", code),
                needLogin = code in setOf("AccessTokenInvalid", "TokenVerifyFailed", "I40001"),
                raw = j.toString()
            )
        }
        return j
    }

    override suspend fun quota(): Map<String, Any?> {
        return try {
            if (mode == "openapi") {
                val j = post("/adrive/v1.0/user/getDriveInfo", JSONObject())
                if (driveId.isBlank()) driveId = j.optString("default_drive_id")
                mapOf("drive_id" to driveId, "total_size" to j.optLong("total_size"), "used_size" to j.optLong("used_size"))
            } else {
                val j = post("/v2/user/get", JSONObject())
                if (driveId.isBlank()) driveId = j.optString("default_drive_id")
                mapOf("drive_id" to driveId, "user_id" to j.optString("user_id"), "nick_name" to j.optString("nick_name"), "mode" to mode)
            }
        } catch (e: AdapterError) {
            mapOf("error" to e.message, "mode" to mode)
        }
    }

    override suspend fun listFiles(parentId: String?): List<CloudFile> {
        if (driveId.isBlank()) quota()
        if (driveId.isBlank()) throw AdapterError(name, "缺少 drive_id", needLogin = true)

        val path = if (mode == "openapi") "/adrive/v1.0/openFile/list" else "/v2/file/list"
        val out = ArrayList<CloudFile>()
        var marker = ""
        while (true) {
            val body = JSONObject()
                .put("drive_id", driveId)
                .put("parent_file_id", parentId ?: rootId)
                .put("limit", 100)
                .put("marker", marker)
            if (mode != "openapi") {
                body.put("fields", "*")
                    .put("order_by", "name")
                    .put("order_direction", "ASC")
                    .put("url_expire_sec", 14400)
            }
            val j = post(path, body)
            val items = j.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                out.add(
                    CloudFile(
                        id = it.optString("file_id"),
                        name = it.optString("name"),
                        isDir = it.optString("type") == "folder",
                        size = it.optLong("size", 0),
                        parentId = parentId ?: rootId,
                        extra = mapOf(
                            "drive_id" to driveId,
                            "category" to it.optString("category"),
                            "file_extension" to it.optString("file_extension"),
                            "thumbnail" to it.optString("thumbnail"),
                            "content_hash" to it.optString("content_hash")
                        )
                    )
                )
            }
            marker = j.optString("next_marker")
            if (marker.isBlank()) break
        }
        return out
    }

    override suspend fun getLink(file: CloudFile): PlaySource {
        val did = (file.extra["drive_id"] as? String)?.ifBlank { driveId } ?: driveId
        val path = if (mode == "openapi") "/adrive/v1.0/openFile/getDownloadUrl" else "/v2/file/get_download_url"
        val j = post(
            path,
            JSONObject().put("drive_id", did).put("file_id", file.id).put("expire_sec", 14400)
        )
        val url = j.optString("url")
        if (url.isBlank()) throw AdapterError(name, "$path 未返回 url", raw = j.toString())
        val hdrs = if (mode == "openapi") emptyMap<String, String>() else mapOf(
            "Referer" to "https://www.aliyundrive.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
        )
        return PlaySource(url, hdrs, System.currentTimeMillis() / 1000 + 3600, file.name)
    }

    /** 视频转码清晰度（流畅播等价）：get_video_preview_play_info → live_transcoding_task_list。 */
    override suspend fun listResolutions(file: CloudFile): List<Map<String, Any?>> {
        val did = (file.extra["drive_id"] as? String)?.ifBlank { driveId } ?: driveId
        val path = if (mode == "openapi") "/adrive/v1.0/openFile/getVideoPreviewPlayInfo"
        else "/v2/file/get_video_preview_play_info"
        val j = post(
            path,
            JSONObject().put("drive_id", did).put("file_id", file.id)
                .put("category", "live_transcoding").put("template_id", "")
                .put("url_expire_sec", 14400)
        )
        val out = ArrayList<Map<String, Any?>>()
        val tasks = j.optJSONObject("video_preview_play_info")
            ?.optJSONArray("live_transcoding_task_list") ?: return out
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            out.add(
                mapOf(
                    "name" to t.optString("template_id").ifBlank { t.optString("template_name") },
                    "url" to t.optString("url"),
                    "status" to t.optString("status", "success"),
                    "headers" to mapOf("Referer" to "https://www.aliyundrive.com/")
                )
            )
        }
        return out
    }
}
