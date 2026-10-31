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

class CloudPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BuiltinAdapters.install()
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
