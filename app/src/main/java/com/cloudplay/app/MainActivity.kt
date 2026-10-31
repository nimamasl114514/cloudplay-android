package com.cloudplay.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "CloudPlay ${BuildConfig.VERSION_NAME}\n多网盘云播 · Android 客户端"
            textSize = 20f
        }
        setContentView(tv)
    }
}
