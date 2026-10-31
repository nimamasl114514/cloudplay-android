package com.cloudplay.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudplay.app.CloudPlayApp
import com.cloudplay.app.R
import com.cloudplay.app.core.AdapterError
import com.cloudplay.app.core.Adapters
import com.cloudplay.app.core.CloudDiskAdapter
import com.cloudplay.app.core.CloudFile
import com.cloudplay.app.store.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var spinner: Spinner
    private lateinit var recycler: RecyclerView
    private lateinit var txtPath: TextView
    private lateinit var txtStatus: TextView

    private val adapterNames: MutableList<String> = ArrayList()
    private val stack = ArrayDeque<Pair<String, String>>()   // id to name
    private var adapter: CloudDiskAdapter? = null
    private val fileAdapter = FileAdapter { onFileClick(it) }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val cookie = res.data?.getStringExtra(LoginActivity.EXTRA_COOKIE).orEmpty()
            val a = adapter ?: return@registerForActivityResult
            if (cookie.isNotBlank()) {
                val old = CredentialStore.load(this, a.name)
                CredentialStore.save(this, a.name, old + ("cookie" to cookie))
                toast("已保存 Cookie（${cookie.length} 字符），重新连接")
                connect()
            } else {
                toast("没有取到 Cookie，请确认已登录")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinner = findViewById(R.id.spinnerDrive)
        recycler = findViewById(R.id.recycler)
        txtPath = findViewById(R.id.txtPath)
        txtStatus = findViewById(R.id.txtStatus)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = fileAdapter

        adapterNames.clear()
        adapterNames.addAll(Adapters.names())
        spinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, adapterNames
        )

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }
        findViewById<Button>(R.id.btnCred).setOnClickListener { showCredDialog() }
        findViewById<Button>(R.id.btnLogin).setOnClickListener { openLogin() }

        connect()
    }

    override fun onBackPressed() {
        if (stack.size > 1) {
            stack.removeLast()
            loadFiles(stack.last().first)
        } else {
            super.onBackPressed()
        }
    }

    private fun currentName(): String =
        adapterNames.getOrElse(spinner.selectedItemPosition) { adapterNames.firstOrNull() ?: "local" }

    private fun connect() {
        val name = currentName()
        val cred = CredentialStore.load(this, name)
        val a = try {
            Adapters.create(name, cred)
        } catch (e: Exception) {
            toast(e.message ?: "创建适配器失败")
            return
        }
        adapter = a
        stack.clear()
        stack.addLast(a.rootId to a.displayName)
        txtStatus.text = "${a.displayName} · ${a.credentialHint()}"
        loadFiles(a.rootId)
    }

    private fun loadFiles(parentId: String) {
        val a = adapter ?: return
        lifecyclerJob {
            txtStatus.text = "加载中…"
            try {
                val list = withContext(Dispatchers.IO) { a.listFiles(parentId) }
                fileAdapter.submit(list)
                txtPath.text = "/" + stack.joinToString("/") { it.second }
                txtStatus.text = "${list.size} 项 · ${a.displayName}"
            } catch (e: AdapterError) {
                txtStatus.text = "失败：${e.message}"
                if (e.needLogin) {
                    toast("需要登录：点「网页登录」或填凭据")
                }
            } catch (e: Exception) {
                txtStatus.text = "失败：${e.message}"
            }
        }
    }

    private fun onFileClick(f: CloudFile) {
        val a = adapter ?: return
        if (f.isDir) {
            stack.addLast(f.id to f.name)
            loadFiles(f.id)
            return
        }
        if (!f.isVideo) {
            toast("不是视频文件：${f.name}")
            return
        }
        lifecyclerJob {
            txtStatus.text = "取直链：${f.name}"
            try {
                val src = withContext(Dispatchers.IO) { a.getLink(f) }
                val playUrl = if (src.url.startsWith("http")) {
                    (application as CloudPlayApp).proxy.register(src.url, src.headers, f.name)
                } else {
                    src.url
                }
                txtStatus.text = "播放：$playUrl"
                startActivity(
                    Intent(this@MainActivity, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_URL, playUrl)
                        .putExtra(PlayerActivity.EXTRA_TITLE, f.name)
                )
            } catch (e: Exception) {
                txtStatus.text = "取链失败：${e.message}"
                toast("取链失败：${e.message}")
            }
        }
    }

    private fun showCredDialog() {
        val a = adapter ?: return
        val old = CredentialStore.load(this, a.name)
        val hint = buildString {
            append("每行一条 key=value。\n需要的字段：")
            append(if (a.needCredential().isEmpty()) "（无强制字段）" else a.needCredential().joinToString("、"))
            append("\n\n示例：\n")
            append("cookie=__pus=xxx; __puus=yyy\n")
            append("token=xxxxx\n")
            append("refresh_token=xxxxx\n")
        }
        val et = EditText(this).apply {
            setText(old.entries.joinToString("\n") { "${it.key}=${it.value}" })
            setMinLines(6)
            setTextSize(12f)
        }
        AlertDialog.Builder(this)
            .setTitle("${a.displayName} 凭据")
            .setMessage(hint)
            .setView(et)
            .setPositiveButton("保存并连接") { _, _ ->
                val m = LinkedHashMap<String, String>()
                for (line in et.text.toString().split("\n")) {
                    val t = line.trim()
                    if (t.isBlank() || !t.contains('=')) continue
                    m[t.substringBefore('=').trim()] = t.substringAfter('=').trim()
                }
                CredentialStore.save(this, a.name, m)
                connect()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openLogin() {
        val name = currentName()
        val (url, domain) = when (name) {
            "quark" -> "https://pan.quark.cn/" to "https://pan.quark.cn"
            "uc" -> "https://drive.uc.cn/" to "https://drive.uc.cn"
            "115" -> "https://115.com/" to "https://115.com"
            "123" -> "https://yun.123pan.com/" to "https://yun.123pan.com"
            "189" -> "https://cloud.189.cn/web/main/" to "https://cloud.189.cn"
            "aliyun" -> "https://www.alipan.com/" to "https://www.alipan.com"
            else -> {
                toast("该网盘无需网页登录")
                return
            }
        }
        loginLauncher.launch(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_URL, url)
                .putExtra(LoginActivity.EXTRA_DOMAIN, domain)
        )
    }

    private fun lifecyclerJob(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
