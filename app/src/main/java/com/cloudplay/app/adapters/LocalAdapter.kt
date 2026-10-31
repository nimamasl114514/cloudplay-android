package com.cloudplay.app.adapters

import com.cloudplay.app.core.CloudDiskAdapter
import com.cloudplay.app.core.CloudFile
import com.cloudplay.app.core.PlaySource
import java.io.File

/**
 * 本地目录适配器 —— 参照实现 / 离线自证。
 *
 * 同一套适配器契约既能接网盘，也能接本地目录，证明内核与存储解耦。
 * credential: `{"path": "/sdcard/Movies"}`
 */
class LocalAdapter(credential: Map<String, String> = emptyMap()) : CloudDiskAdapter(credential) {

    override val name = "local"
    override val displayName = "本地目录"

    private val root: File by lazy {
        val p = credential["path"].orEmpty()
        if (p.isNotBlank()) File(p) else File("/sdcard")
    }

    override val rootId: String get() = root.absolutePath

    override fun credentialHint(): String = "需要：path（本地目录绝对路径）"

    override suspend fun listFiles(parentId: String?): List<CloudFile> {
        val dir = File(parentId ?: rootId)
        if (!dir.isDirectory) return emptyList()
        val list = dir.listFiles() ?: return emptyList()
        return list.map {
            CloudFile(
                id = it.absolutePath,
                name = it.name,
                isDir = it.isDirectory,
                size = if (it.isFile) it.length() else 0L,
                parentId = dir.absolutePath
            )
        }.sortedWith(compareByDescending<CloudFile> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun getLink(file: CloudFile): PlaySource {
        val f = File(file.id)
        if (!f.exists()) throw com.cloudplay.app.core.AdapterError(name, "文件不存在：${file.id}")
        return PlaySource(url = f.toURI().toString(), fileName = f.name)
    }
}
