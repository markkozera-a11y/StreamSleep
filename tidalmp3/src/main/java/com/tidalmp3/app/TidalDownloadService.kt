package com.tidalmp3.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class TidalDownloadService : Service() {

    companion object {
        const val TAG = "TidalDownload"

        const val ACTION_DOWNLOAD = "ACTION_DOWNLOAD"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val EXTRA_TRACK_IDS = "EXTRA_TRACK_IDS"
        const val EXTRA_TRACK_TITLES = "EXTRA_TRACK_TITLES"
        const val EXTRA_TRACK_ARTISTS = "EXTRA_TRACK_ARTISTS"
        const val EXTRA_PLAYLIST_NAME = "EXTRA_PLAYLIST_NAME"
        const val EXTRA_QUALITY = "EXTRA_QUALITY"

        const val CHANNEL_ID = "tidal_download_channel"
        const val NOTIFICATION_ID = 2

        const val BROADCAST_PROGRESS = "com.tidalmp3.app.DOWNLOAD_PROGRESS"
        const val EXTRA_CURRENT = "EXTRA_CURRENT"
        const val EXTRA_TOTAL = "EXTRA_TOTAL"
        const val EXTRA_TRACK_NAME = "EXTRA_TRACK_NAME"
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val EXTRA_ERROR_MSG = "EXTRA_ERROR_MSG"
        const val EXTRA_BYTES_DOWNLOADED = "EXTRA_BYTES_DOWNLOADED"
        const val EXTRA_BYTES_TOTAL = "EXTRA_BYTES_TOTAL"

        var isRunning = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val tidalApi = TidalApiClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val trackIds = intent.getIntArrayExtra(EXTRA_TRACK_IDS) ?: return START_NOT_STICKY
                val titles = intent.getStringArrayExtra(EXTRA_TRACK_TITLES) ?: return START_NOT_STICKY
                val artists = intent.getStringArrayExtra(EXTRA_TRACK_ARTISTS) ?: return START_NOT_STICKY
                val playlistName = intent.getStringExtra(EXTRA_PLAYLIST_NAME) ?: "Tidal"
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "HIGH"
                startDownloads(trackIds, titles, artists, playlistName, quality)
            }
            ACTION_CANCEL -> {
                scope.coroutineContext.cancelChildren()
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownloads(
        trackIds: IntArray,
        titles: Array<String>,
        artists: Array<String>,
        playlistName: String,
        quality: String
    ) {
        isRunning = true
        val notification = buildNotification("Przygotowywanie...", 0, trackIds.size)
        startForeground(NOTIFICATION_ID, notification)

        scope.launch {
            val outputDir = getOutputDir(playlistName)
            var successCount = 0
            Log.d(TAG, "Rozpoczynam pobieranie ${trackIds.size} utworow, jakosc: $quality, folder: ${outputDir.absolutePath}")

            for (i in trackIds.indices) {
                if (!isActive) break

                val trackId = trackIds[i]
                val title = titles[i]
                val artist = artists[i]
                val displayName = "$artist - $title"

                Log.d(TAG, "Pobieram ${i+1}/${trackIds.size}: $displayName (ID: $trackId)")
                sendProgressBroadcast(i + 1, trackIds.size, displayName, "downloading")
                updateNotification("${i + 1}/${trackIds.size}: $displayName", i, trackIds.size)

                try {
                    val streamUrl = tidalApi.getStreamUrl(trackId, quality)
                    Log.d(TAG, "URL streamu: ${streamUrl.take(80)}...")
                    val fileName = sanitizeFileName("$artist - $title") + ".mp3"
                    val outputFile = File(outputDir, fileName)

                    downloadFileWithProgress(streamUrl, outputFile, i + 1, trackIds.size, displayName)
                    successCount++
                    Log.d(TAG, "Pobrano: $fileName (${outputFile.length()} bajtow)")
                    sendProgressBroadcast(i + 1, trackIds.size, displayName, "track_done")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Blad przy $displayName: ${e.message}", e)
                    sendProgressBroadcast(i + 1, trackIds.size, displayName, "error", e.message)
                }
            }

            withContext(Dispatchers.Main) {
                Log.d(TAG, "Pobieranie zakonczone: $successCount/${trackIds.size}")
                sendProgressBroadcast(successCount, trackIds.size, "", "done")
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)

                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val doneNotification = NotificationCompat.Builder(this@TidalDownloadService, CHANNEL_ID)
                    .setContentTitle("Pobieranie zakonczone")
                    .setContentText("Pobrano $successCount z ${trackIds.size} utworow do: $playlistName/")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTIFICATION_ID + 1, doneNotification)
                stopSelf()
            }
        }
    }

    private fun downloadFileWithProgress(
        url: String,
        outputFile: File,
        currentTrack: Int,
        totalTracks: Int,
        displayName: String
    ) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body ?: throw Exception("Pusta odpowiedz")
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgressUpdate = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        // Aktualizuj postep co 50KB
                        if (totalBytesRead - lastProgressUpdate > 50 * 1024) {
                            lastProgressUpdate = totalBytesRead
                            sendProgressBroadcast(
                                currentTrack, totalTracks, displayName, "downloading",
                                bytesDownloaded = totalBytesRead, bytesTotal = contentLength
                            )
                            updateNotification(
                                "$currentTrack/$totalTracks: $displayName (${formatBytes(totalBytesRead)})",
                                currentTrack - 1, totalTracks
                            )
                        }
                    }
                }
            }
        }
    }

    private fun getOutputDir(playlistName: String): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(musicDir, sanitizeFileName(playlistName))
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    private fun sendProgressBroadcast(
        current: Int,
        total: Int,
        trackName: String,
        status: String,
        errorMsg: String? = null,
        bytesDownloaded: Long = 0,
        bytesTotal: Long = 0
    ) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_CURRENT, current)
            putExtra(EXTRA_TOTAL, total)
            putExtra(EXTRA_TRACK_NAME, trackName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_BYTES_DOWNLOADED, bytesDownloaded)
            putExtra(EXTRA_BYTES_TOTAL, bytesTotal)
            errorMsg?.let { putExtra(EXTRA_ERROR_MSG, it) }
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        val cancelIntent = Intent(this, TidalDownloadService::class.java).apply { action = ACTION_CANCEL }
        val cancelPendingIntent = PendingIntent.getService(this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pobieranie z Tidal")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(total, current, current == 0 && total == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Anuluj", cancelPendingIntent)
            .build()
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, current, total))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Pobieranie z Tidal", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Powiadomienia o pobieraniu utworow z Tidal"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        isRunning = false
    }
}
