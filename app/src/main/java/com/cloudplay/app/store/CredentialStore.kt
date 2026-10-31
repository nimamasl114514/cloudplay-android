package com.cloudplay.app.store

import android.content.Context
import org.json.JSONObject

/** 凭据存储：只落 App 私有 SharedPreferences，不上传、不写日志。 */
object CredentialStore {

    private const val PREF = "cloudplay_creds"

    fun load(ctx: Context, adapter: String): Map<String, String> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(adapter, null) ?: return emptyMap()
        return try {
            val j = JSONObject(raw)
            val m = LinkedHashMap<String, String>()
            for (k in j.keys()) m[k] = j.optString(k)
            m
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(ctx: Context, adapter: String, values: Map<String, String>) {
        val j = JSONObject()
        for ((k, v) in values) j.put(k, v)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(adapter, j.toString()).apply()
    }

    fun clear(ctx: Context, adapter: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(adapter).apply()
    }
}
