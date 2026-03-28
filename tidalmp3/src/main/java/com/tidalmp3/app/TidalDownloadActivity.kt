package com.tidalmp3.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
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
import java.io.File

class TidalDownloadActivity : AppCompatActivity() {

    private lateinit var etPlaylistUrl: EditText
    private lateinit var btnFetch: Button
    private lateinit var btnDownload: Button
    private lateinit var btnSelectAll: Button
    private lateinit var spinnerQuality: Spinner
    private lateinit var tvPlaylistInfo: TextView
    private lateinit var tvOutputFolder: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvProgressDetail: TextView
    private lateinit var progressBarTotal: ProgressBar
    private lateinit var progressBarFile: ProgressBar
    private lateinit var rvTracks: RecyclerView

    private val tidalApi = TidalApiClient()
    private var tracks = listOf<TidalTrack>()
    private val selectedTrackIds = mutableSetOf<Int>()
    private var adapter: TrackAdapter? = null
    private var playlistTitle = ""
    private var allSelected = false

    data class QualityOption(val label: String, val apiValue: String)

    private val qualityOptions = listOf(
        QualityOption("96 kbps", "LOW"),
        QualityOption("160 kbps", "HIGH"),
        QualityOption("192 kbps", "HIGH"),
        QualityOption("320 kbps", "HIGH")
    )

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = intent.getIntExtra(TidalDownloadService.EXTRA_CURRENT, 0)
            val total = intent.getIntExtra(TidalDownloadService.EXTRA_TOTAL, 0)
            val trackName = intent.getStringExtra(TidalDownloadService.EXTRA_TRACK_NAME) ?: ""
            val status = intent.getStringExtra(TidalDownloadService.EXTRA_STATUS) ?: ""
            val bytesDownloaded = intent.getLongExtra(TidalDownloadService.EXTRA_BYTES_DOWNLOADED, 0)
            val bytesTotal = intent.getLongExtra(TidalDownloadService.EXTRA_BYTES_TOTAL, 0)

            when (status) {
                "downloading" -> {
                    progressBarTotal.visibility = View.VISIBLE
                    progressBarFile.visibility = View.VISIBLE
                    progressBarTotal.max = total
                    progressBarTotal.progress = current - 1
                    tvProgress.text = "Pobieranie $current / $total: $trackName"
                    if (bytesTotal > 0) {
                        progressBarFile.isIndeterminate = false
                        progressBarFile.max = 100
                        progressBarFile.progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                        tvProgressDetail.text = "${formatBytes(bytesDownloaded)} / ${formatBytes(bytesTotal)}"
                    } else {
                        progressBarFile.isIndeterminate = true
                        tvProgressDetail.text = "${formatBytes(bytesDownloaded)} pobrano"
                    }
                    tvProgressDetail.visibility = View.VISIBLE
                }
                "track_done" -> {
                    progressBarTotal.progress = current
                }
                "done" -> {
                    progressBarTotal.visibility = View.GONE
                    progressBarFile.visibility = View.GONE
                    tvProgressDetail.visibility = View.GONE
                    val outputPath = getOutputPath()
                    tvProgress.text = "Pobrano $current z $total utworow\n→ $outputPath"
                    btnDownload.isEnabled = true
                    btnFetch.isEnabled = true
                }
                "error" -> {
                    val errorMsg = intent.getStringExtra(TidalDownloadService.EXTRA_ERROR_MSG) ?: "Nieznany blad"
                    tvProgressDetail.visibility = View.VISIBLE
                    tvProgressDetail.text = "Blad: $trackName - $errorMsg"
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
        spinnerQuality = findViewById(R.id.spinnerQuality)
        tvPlaylistInfo = findViewById(R.id.tvPlaylistInfo)
        tvOutputFolder = findViewById(R.id.tvOutputFolder)
        tvProgress = findViewById(R.id.tvProgress)
        tvProgressDetail = findViewById(R.id.tvProgressDetail)
        progressBarTotal = findViewById(R.id.progressBarTotal)
        progressBarFile = findViewById(R.id.progressBarFile)
        rvTracks = findViewById(R.id.rvTracks)

        rvTracks.layoutManager = LinearLayoutManager(this)

        setupQualitySpinner()
        btnFetch.setOnClickListener { fetchPlaylist() }
        btnDownload.setOnClickListener { startDownload() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }

        checkPermissions()
    }

    private fun setupQualitySpinner() {
        val labels = qualityOptions.map { it.label }
        spinnerQuality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        // Domyslnie 320 kbps
        spinnerQuality.setSelection(3)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TidalDownloadService.BROADCAST_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(progressReceiver, filter)
        }
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
        tvOutputFolder.visibility = View.GONE
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

                // Pokaz folder docelowy
                val outputPath = getOutputPath()
                tvOutputFolder.text = "Folder: $outputPath"
                tvOutputFolder.visibility = View.VISIBLE

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
        val quality = qualityOptions[spinnerQuality.selectedItemPosition].apiValue

        val intent = Intent(this, TidalDownloadService::class.java).apply {
            action = TidalDownloadService.ACTION_DOWNLOAD
            putExtra(TidalDownloadService.EXTRA_TRACK_IDS, trackIds)
            putExtra(TidalDownloadService.EXTRA_TRACK_TITLES, titles)
            putExtra(TidalDownloadService.EXTRA_TRACK_ARTISTS, artists)
            putExtra(TidalDownloadService.EXTRA_PLAYLIST_NAME, playlistTitle)
            putExtra(TidalDownloadService.EXTRA_QUALITY, quality)
        }
        startForegroundService(intent)

        btnDownload.isEnabled = false
        btnFetch.isEnabled = false
        tvProgress.text = "Rozpoczynanie pobierania..."
        progressBarTotal.visibility = View.VISIBLE
        progressBarTotal.isIndeterminate = true
        progressBarFile.visibility = View.VISIBLE
        progressBarFile.isIndeterminate = true
        tvProgressDetail.visibility = View.VISIBLE
        tvProgressDetail.text = "Laczenie z Tidal..."
    }

    private fun getOutputPath(): String {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val safeName = playlistTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return "${musicDir.absolutePath}/$safeName/"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        }
    }
}
