package com.cloudplay.app

import android.app.Application
import com.cloudplay.app.adapters.AliyunAdapter
import com.cloudplay.app.adapters.Cloud189Adapter
import com.cloudplay.app.adapters.LocalAdapter
import com.cloudplay.app.adapters.Pan115Adapter
import com.cloudplay.app.adapters.Pan123Adapter
import com.cloudplay.app.adapters.QuarkAdapter
import com.cloudplay.app.adapters.UcAdapter
import com.cloudplay.app.core.Adapters
import com.cloudplay.app.stream.StreamProxy

class CloudPlayApp : Application() {

    /** 全局流代理：所有网盘直链统一包装成 http://127.0.0.1:<port>/s/<token>。 */
    lateinit var proxy: StreamProxy
        private set

    override fun onCreate() {
        super.onCreate()
        BuiltinAdapters.install()
        proxy = StreamProxy().also { it.start() }
    }
}

/** 内置网盘装配点：新增网盘只改这里一行。 */
object BuiltinAdapters {
    fun install() {
        Adapters.register("local") { LocalAdapter(it) }
        Adapters.register("quark") { QuarkAdapter(it) }
        Adapters.register("uc") { UcAdapter(it) }
        Adapters.register("aliyun") { AliyunAdapter(it) }
        Adapters.register("123") { Pan123Adapter(it) }
        Adapters.register("115") { Pan115Adapter(it) }
        Adapters.register("189") { Cloud189Adapter(it) }
    }
}
