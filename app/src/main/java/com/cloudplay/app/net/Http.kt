package com.cloudplay.app.net

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OkHttp 统一底座：query 拼装 + JSON 解析 + 可关重定向（189/123 取签名直链用）。 */
object Http {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val noRedirect: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val EMPTY = ByteArray(0).toRequestBody(null)

    data class Resp(
        val code: Int,
        val body: String,
        val json: JSONObject?,
        val headers: Headers
    )

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        params: Map<String, Any?> = emptyMap(),
        jsonBody: JSONObject? = null,
        followRedirects: Boolean = true
    ): Resp {
        var builder = url.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("bad url: $url")
        for ((k, v) in params) {
            if (v != null) builder = builder.addQueryParameter(k, v.toString())
        }
        val rb = Request.Builder().url(builder.build())
        for ((k, v) in headers) rb.header(k, v)
        val body = jsonBody?.toString()?.toRequestBody(JSON)
        when (method.uppercase()) {
            "GET" -> rb.get()
            "POST" -> rb.post(body ?: EMPTY)
            "HEAD" -> rb.head()
            else -> rb.method(method.uppercase(), body)
        }
        val c = if (followRedirects) client else noRedirect
        c.newCall(rb.build()).execute().use { r ->
            val text = try {
                r.body?.string().orEmpty()
            } catch (_: Exception) {
                ""
            }
            val json = try {
                if (text.isNotBlank()) JSONObject(text) else null
            } catch (_: Exception) {
                null
            }
            return Resp(r.code, text, json, r.headers)
        }
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        params: Map<String, Any?> = emptyMap()
    ): Resp = request("GET", url, headers, params)

    fun postJson(
        url: String,
        body: JSONObject,
        headers: Map<String, String> = emptyMap(),
        params: Map<String, Any?> = emptyMap()
    ): Resp = request("POST", url, headers, params, body)
}
