package com.tidalmp3.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ConverterActivity : AppCompatActivity() {

    private lateinit var btnPickFiles: Button
    private lateinit var btnConvert: Button
    private lateinit var btnClear: Button
    private lateinit var spinnerQuality: Spinner
    private lateinit var tvFileCount: TextView
    private lateinit var tvOutputFolder: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvProgressDetail: TextView
    private lateinit var progressBarTotal: ProgressBar
    private lateinit var progressBarFile: ProgressBar
    private lateinit var rvFiles: RecyclerView

    private val audioFiles = mutableListOf<AudioFileItem>()
    private var adapter: AudioFileAdapter? = null

    data class QualityOption(val label: String, val kbps: Int)

    private val qualityOptions = listOf(
        QualityOption("96 kbps", 96),
        QualityOption("160 kbps", 160),
        QualityOption("192 kbps", 192),
        QualityOption("320 kbps", 320)
    )

    private val outputDir: String
        get() {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            return "${musicDir.absolutePath}/Converted_MP3/"
        }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            addFiles(uris)
        }
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = intent.getIntExtra(ConvertService.EXTRA_CURRENT, 0)
            val total = intent.getIntExtra(ConvertService.EXTRA_TOTAL, 0)
            val fileName = intent.getStringExtra(ConvertService.EXTRA_FILE_NAME) ?: ""
            val status = intent.getStringExtra(ConvertService.EXTRA_STATUS) ?: ""
            val percent = intent.getIntExtra(ConvertService.EXTRA_PERCENT, 0)

            when (status) {
                "converting" -> {
                    progressBarTotal.visibility = View.VISIBLE
                    progressBarFile.visibility = View.VISIBLE
                    progressBarTotal.isIndeterminate = false
                    progressBarTotal.max = total
                    progressBarTotal.progress = current - 1
                    progressBarFile.isIndeterminate = false
                    progressBarFile.max = 100
                    progressBarFile.progress = percent
                    tvProgress.text = "Konwersja $current / $total: $fileName"
                    tvProgressDetail.visibility = View.VISIBLE
                    tvProgressDetail.text = "$percent%"
                }
                "file_done" -> {
                    progressBarTotal.progress = current
                    progressBarFile.progress = 100
                }
                "done" -> {
                    progressBarTotal.visibility = View.GONE
                    progressBarFile.visibility = View.GONE
                    tvProgressDetail.visibility = View.GONE
                    tvProgress.text = "Skonwertowano $current z $total plikow\n→ $outputDir"
                    btnConvert.isEnabled = true
                    btnPickFiles.isEnabled = true
                }
                "error" -> {
                    val errorMsg = intent.getStringExtra(ConvertService.EXTRA_ERROR_MSG) ?: ""
                    tvProgressDetail.visibility = View.VISIBLE
                    tvProgressDetail.text = "Blad: $fileName - $errorMsg"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_converter)

        btnPickFiles = findViewById(R.id.btnPickFiles)
        btnConvert = findViewById(R.id.btnConvert)
        btnClear = findViewById(R.id.btnClear)
        spinnerQuality = findViewById(R.id.spinnerQuality)
        tvFileCount = findViewById(R.id.tvFileCount)
        tvOutputFolder = findViewById(R.id.tvOutputFolder)
        tvProgress = findViewById(R.id.tvProgress)
        tvProgressDetail = findViewById(R.id.tvProgressDetail)
        progressBarTotal = findViewById(R.id.progressBarTotal)
        progressBarFile = findViewById(R.id.progressBarFile)
        rvFiles = findViewById(R.id.rvFiles)

        rvFiles.layoutManager = LinearLayoutManager(this)
        adapter = AudioFileAdapter(audioFiles) { position ->
            audioFiles.removeAt(position)
            adapter?.notifyItemRemoved(position)
            updateFileCount()
        }
        rvFiles.adapter = adapter

        setupQualitySpinner()

        btnPickFiles.setOnClickListener {
            pickFiles.launch(arrayOf("audio/*"))
        }
        btnConvert.setOnClickListener { startConversion() }
        btnClear.setOnClickListener {
            audioFiles.clear()
            adapter?.notifyDataSetChanged()
            updateFileCount()
        }

        tvOutputFolder.text = "Folder wyjsciowy: $outputDir"
        checkPermissions()
    }

    private fun setupQualitySpinner() {
        val labels = qualityOptions.map { it.label }
        spinnerQuality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinnerQuality.setSelection(3) // 320 kbps
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ConvertService.BROADCAST_PROGRESS)
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

    private fun addFiles(uris: List<Uri>) {
        for (uri in uris) {
            // Persist read permission
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val name = getFileName(uri) ?: "Nieznany plik"
            val size = getFileSize(uri)

            // Skip duplicates
            if (audioFiles.any { it.uri == uri }) continue

            audioFiles.add(AudioFileItem(uri, name, size))
        }
        adapter?.notifyDataSetChanged()
        updateFileCount()
    }

    private fun getFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun getFileSize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) return cursor.getLong(idx)
            }
        }
        return 0
    }

    private fun updateFileCount() {
        val count = audioFiles.size
        tvFileCount.text = when {
            count == 0 -> "Brak plikow - kliknij WYBIERZ PLIKI"
            count == 1 -> "1 plik"
            count < 5 -> "$count pliki"
            else -> "$count plikow"
        }
        btnConvert.visibility = if (count > 0) View.VISIBLE else View.GONE
        btnClear.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun startConversion() {
        if (audioFiles.isEmpty()) return

        val uris = audioFiles.map { it.uri.toString() }.toTypedArray()
        val names = audioFiles.map { it.name }.toTypedArray()
        val kbps = qualityOptions[spinnerQuality.selectedItemPosition].kbps

        val intent = Intent(this, ConvertService::class.java).apply {
            action = ConvertService.ACTION_CONVERT
            putExtra(ConvertService.EXTRA_URIS, uris)
            putExtra(ConvertService.EXTRA_NAMES, names)
            putExtra(ConvertService.EXTRA_KBPS, kbps)
        }
        startForegroundService(intent)

        btnConvert.isEnabled = false
        btnPickFiles.isEnabled = false
        tvProgress.text = "Rozpoczynanie konwersji..."
        progressBarTotal.visibility = View.VISIBLE
        progressBarTotal.isIndeterminate = true
        progressBarFile.visibility = View.VISIBLE
        progressBarFile.isIndeterminate = true
        tvProgressDetail.visibility = View.VISIBLE
        tvProgressDetail.text = "Przygotowywanie FFmpeg..."
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
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

data class AudioFileItem(
    val uri: Uri,
    val name: String,
    val size: Long
) {
    val sizeFormatted: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
            else -> "%.1f MB".format(size / (1024.0 * 1024.0))
        }

    val outputName: String
        get() {
            val base = name.substringBeforeLast(".")
            return "$base.mp3"
        }
}
