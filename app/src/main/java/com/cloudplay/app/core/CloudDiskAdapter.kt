package com.cloudplay.app.core

/**
 * 网盘适配器抽象基类 —— 唯一扩展点。
 *
 * 子类最小实现面：listFiles / getLink。
 * 新网盘 = 新增一个子类 + 在 BuiltinAdapters 里登记一行，UI/播放/代理零改动。
 */
abstract class CloudDiskAdapter(val credential: Map<String, String> = emptyMap()) {

    abstract val name: String
    abstract val displayName: String

    /** 根目录 id（各网盘不同：阿里 root / 115 0 / 123 0 / 189 -11） */
    open val rootId: String = "-1"

    open fun needCredential(): List<String> = emptyList()

    open fun credentialHint(): String =
        if (needCredential().isEmpty()) "无需凭据" else "需要：" + needCredential().joinToString("、")

    open suspend fun login() {}

    abstract suspend fun listFiles(parentId: String? = null): List<CloudFile>

    abstract suspend fun getLink(file: CloudFile): PlaySource

    open suspend fun quota(): Map<String, Any?> = emptyMap()

    /** 转码清晰度列表（「流畅播」等价能力，可选） */
    open suspend fun listResolutions(file: CloudFile): List<Map<String, Any?>> = emptyList()

    /** 递归收集视频（内核通用实现） */
    open suspend fun walkVideos(
        parentId: String? = null,
        maxDepth: Int = 3,
        limit: Int = 500
    ): List<CloudFile> {
        val out = ArrayList<CloudFile>()
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast((parentId ?: rootId) to 0)
        while (stack.isNotEmpty() && out.size < limit) {
            val (pid, depth) = stack.removeLast()
            val items = try {
                listFiles(pid)
            } catch (e: AdapterError) {
                continue
            }
            for (it in items) {
                if (it.isDir) {
                    if (depth + 1 < maxDepth) stack.addLast(it.id to depth + 1)
                } else if (it.isVideo) {
                    out.add(it)
                }
            }
        }
        return out
    }
}

/** 适配器注册表（工厂函数，避免反射）。 */
object Adapters {

    private val factories = LinkedHashMap<String, (Map<String, String>) -> CloudDiskAdapter>()

    fun register(name: String, factory: (Map<String, String>) -> CloudDiskAdapter) {
        factories[name.lowercase()] = factory
    }

    fun create(name: String, credential: Map<String, String>): CloudDiskAdapter {
        val f = factories[name.lowercase()]
            ?: throw AdapterError(name, "未知网盘：$name")
        return f(credential)
    }

    fun names(): List<String> = factories.keys.toList()

    fun all(): List<CloudDiskAdapter> = factories.values.map { it(emptyMap()) }
}
