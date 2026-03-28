package com.streamsleep.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class TidalDownloadActivity : AppCompatActivity() {

    private lateinit var etPlaylistUrl: EditText
    private lateinit var btnFetch: Button
    private lateinit var btnDownload: Button
    private lateinit var btnSelectAll: Button
    private lateinit var tvPlaylistInfo: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var rvTracks: RecyclerView

    private val tidalApi = TidalApiClient()
    private var tracks = listOf<TidalTrack>()
    private val selectedTrackIds = mutableSetOf<Int>()
    private var adapter: TrackAdapter? = null
    private var playlistTitle = ""
    private var allSelected = false

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = intent.getIntExtra(TidalDownloadService.EXTRA_CURRENT, 0)
            val total = intent.getIntExtra(TidalDownloadService.EXTRA_TOTAL, 0)
            val trackName = intent.getStringExtra(TidalDownloadService.EXTRA_TRACK_NAME) ?: ""
            val status = intent.getStringExtra(TidalDownloadService.EXTRA_STATUS) ?: ""

            when (status) {
                "downloading" -> {
                    progressBar.visibility = View.VISIBLE
                    progressBar.max = total
                    progressBar.progress = current
                    tvProgress.text = "Pobieranie $current/$total: $trackName"
                }
                "done" -> {
                    progressBar.visibility = View.GONE
                    tvProgress.text = "Pobrano $current z $total utworow do Music/$playlistTitle/"
                    btnDownload.isEnabled = true
                    btnFetch.isEnabled = true
                }
                "error" -> {
                    val errorMsg = intent.getStringExtra(TidalDownloadService.EXTRA_ERROR_MSG) ?: "Nieznany blad"
                    tvProgress.text = "Blad przy $trackName: $errorMsg"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tidal_download)

        etPlaylistUrl = findViewById(R.id.etPlaylistUrl)
        btnFetch = findViewById(R.id.btnFetch)
        btnDownload = findViewById(R.id.btnDownload)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        tvPlaylistInfo = findViewById(R.id.tvPlaylistInfo)
        tvProgress = findViewById(R.id.tvProgress)
        progressBar = findViewById(R.id.progressBar)
        rvTracks = findViewById(R.id.rvTracks)

        rvTracks.layoutManager = LinearLayoutManager(this)

        btnFetch.setOnClickListener { fetchPlaylist() }
        btnDownload.setOnClickListener { startDownload() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        checkStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(progressReceiver, IntentFilter(TidalDownloadService.BROADCAST_PROGRESS),
            RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(progressReceiver)
    }

    private fun fetchPlaylist() {
        val url = etPlaylistUrl.text.toString().trim()
        val playlistId = tidalApi.parsePlaylistId(url)
        if (playlistId == null) {
            Toast.makeText(this, "Nieprawidlowy link do playlisty Tidal", Toast.LENGTH_SHORT).show()
            return
        }

        btnFetch.isEnabled = false
        tvPlaylistInfo.text = "Ladowanie..."
        tracks = emptyList()
        selectedTrackIds.clear()
        rvTracks.adapter = null
        btnDownload.visibility = View.GONE
        btnSelectAll.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val playlist = tidalApi.getPlaylist(playlistId)
                playlistTitle = playlist.title
                tvPlaylistInfo.text = "${playlist.title} (${playlist.numberOfTracks} utworow)"

                tracks = tidalApi.getPlaylistTracks(playlistId)
                selectedTrackIds.addAll(tracks.map { it.id })
                allSelected = true

                adapter = TrackAdapter(tracks, selectedTrackIds)
                rvTracks.adapter = adapter

                btnDownload.visibility = View.VISIBLE
                btnSelectAll.visibility = View.VISIBLE
                btnSelectAll.text = "ODZNACZ WSZYSTKIE"
            } catch (e: Exception) {
                tvPlaylistInfo.text = "Blad: ${e.message}"
            } finally {
                btnFetch.isEnabled = true
            }
        }
    }

    private fun toggleSelectAll() {
        if (allSelected) {
            adapter?.deselectAll()
            btnSelectAll.text = "ZAZNACZ WSZYSTKIE"
        } else {
            adapter?.selectAll()
            btnSelectAll.text = "ODZNACZ WSZYSTKIE"
        }
        allSelected = !allSelected
    }

    private fun startDownload() {
        if (selectedTrackIds.isEmpty()) {
            Toast.makeText(this, "Zaznacz utwory do pobrania", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedTracks = tracks.filter { it.id in selectedTrackIds }
        val trackIds = selectedTracks.map { it.id }.toIntArray()
        val titles = selectedTracks.map { it.title }.toTypedArray()
        val artists = selectedTracks.map { it.artistName }.toTypedArray()

        val intent = Intent(this, TidalDownloadService::class.java).apply {
            action = TidalDownloadService.ACTION_DOWNLOAD
            putExtra(TidalDownloadService.EXTRA_TRACK_IDS, trackIds)
            putExtra(TidalDownloadService.EXTRA_TRACK_TITLES, titles)
            putExtra(TidalDownloadService.EXTRA_TRACK_ARTISTS, artists)
            putExtra(TidalDownloadService.EXTRA_PLAYLIST_NAME, playlistTitle)
        }
        startForegroundService(intent)

        btnDownload.isEnabled = false
        btnFetch.isEnabled = false
        tvProgress.text = "Rozpoczynanie pobierania..."
        progressBar.visibility = View.VISIBLE
    }

    private fun checkStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
            }
        }
    }
}
