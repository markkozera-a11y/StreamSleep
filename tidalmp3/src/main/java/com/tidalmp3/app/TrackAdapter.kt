package com.tidalmp3.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(
    private val tracks: List<TidalTrack>,
    private val selectedIds: MutableSet<Int>
) : RecyclerView.Adapter<TrackAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbSelect: CheckBox = view.findViewById(R.id.cbSelect)
        val tvTitle: TextView = view.findViewById(R.id.tvTrackTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvTrackArtist)
        val tvDuration: TextView = view.findViewById(R.id.tvTrackDuration)
        val tvNumber: TextView = view.findViewById(R.id.tvTrackNumber)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        holder.tvNumber.text = "${position + 1}"
        holder.tvTitle.text = track.title
        holder.tvArtist.text = track.artistName
        holder.tvDuration.text = track.durationFormatted
        holder.cbSelect.isChecked = track.id in selectedIds

        holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedIds.add(track.id) else selectedIds.remove(track.id)
        }
        holder.itemView.setOnClickListener {
            holder.cbSelect.isChecked = !holder.cbSelect.isChecked
        }
    }

    override fun getItemCount() = tracks.size

    fun selectAll() {
        selectedIds.addAll(tracks.map { it.id })
        notifyDataSetChanged()
    }

    fun deselectAll() {
        selectedIds.clear()
        notifyDataSetChanged()
    }
}
