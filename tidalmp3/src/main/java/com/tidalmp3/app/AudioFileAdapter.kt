package com.tidalmp3.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AudioFileAdapter(
    private val files: List<AudioFileItem>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<AudioFileAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvNumber: TextView = view.findViewById(R.id.tvFileNumber)
        val tvName: TextView = view.findViewById(R.id.tvFileName)
        val tvInfo: TextView = view.findViewById(R.id.tvFileInfo)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_audio_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.tvNumber.text = "${position + 1}"
        holder.tvName.text = file.name
        holder.tvInfo.text = "${file.sizeFormatted} → ${file.outputName}"
        holder.btnRemove.setOnClickListener {
            onRemove(holder.adapterPosition)
        }
    }

    override fun getItemCount() = files.size
}
