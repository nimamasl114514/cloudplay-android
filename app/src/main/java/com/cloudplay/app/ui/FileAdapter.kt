package com.cloudplay.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cloudplay.app.R
import com.cloudplay.app.core.CloudFile

class FileAdapter(private val onClick: (CloudFile) -> Unit) :
    RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = ArrayList<CloudFile>()

    fun submit(list: List<CloudFile>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = items[position]
        holder.icon.text = when {
            f.isDir -> "📁"
            f.isVideo -> "🎬"
            else -> "📄"
        }
        holder.name.text = f.name
        holder.meta.text = when {
            f.isDir -> "目录"
            f.isVideo -> "视频 · ${formatSize(f.size)}"
            else -> formatSize(f.size)
        }
        holder.itemView.setOnClickListener { onClick(f) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.txtIcon)
        val name: TextView = v.findViewById(R.id.txtName)
        val meta: TextView = v.findViewById(R.id.txtMeta)
    }

    companion object {
        fun formatSize(b: Long): String = when {
            b <= 0 -> "-"
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
            b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024)
            else -> String.format("%.2f GB", b / 1024.0 / 1024 / 1024)
        }
    }
}
