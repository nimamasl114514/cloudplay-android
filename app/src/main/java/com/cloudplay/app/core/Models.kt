package com.cloudplay.app.core

/** 统一文件/目录模型（各网盘差异在适配层消化）。 */
data class CloudFile(
    val id: String,
    val name: String,
    val isDir: Boolean = false,
    val size: Long = 0L,
    val parentId: String = "",
    val extra: Map<String, Any?> = emptyMap()
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()

    val isVideo: Boolean get() = !isDir && ext in VIDEO_EXTS

    companion object {
        val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "mov", "flv", "ts", "m3u8", "webm",
            "rmvb", "wmv", "m4v", "mpg", "mpeg", "f4v", "rm", "3gp"
        )
    }
}

/** 可直接播放/下载的直链 + 必须携带的请求头。 */
data class PlaySource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** 过期时间戳（秒），0 表示未知 */
    val expireAt: Long = 0L,
    val fileName: String = ""
) {
    val expired: Boolean
        get() = expireAt > 0 && System.currentTimeMillis() / 1000 >= expireAt
}

class AdapterError(
    val adapter: String,
    message: String,
    val needLogin: Boolean = false,
    val code: Int = 0,
    val raw: Any? = null
) : Exception(message)
