package com.cloudplay.app

import android.app.Application
import com.cloudplay.app.adapters.LocalAdapter
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
    }
}
