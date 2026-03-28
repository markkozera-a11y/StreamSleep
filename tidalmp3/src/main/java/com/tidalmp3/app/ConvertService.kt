package com.tidalmp3.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class ConvertService : Service() {

    companion object {
        const val TAG = "ConvertService"

        const val ACTION_CONVERT = "ACTION_CONVERT"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        const val EXTRA_URIS = "EXTRA_URIS"
        const val EXTRA_NAMES = "EXTRA_NAMES"
        const val EXTRA_KBPS = "EXTRA_KBPS"

        const val CHANNEL_ID = "convert_channel"
        const val NOTIFICATION_ID = 3

        const val BROADCAST_PROGRESS = "com.tidalmp3.app.CONVERT_PROGRESS"
        const val EXTRA_CURRENT = "EXTRA_CURRENT"
        const val EXTRA_TOTAL = "EXTRA_TOTAL"
        const val EXTRA_FILE_NAME = "EXTRA_FILE_NAME"
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val EXTRA_ERROR_MSG = "EXTRA_ERROR_MSG"
        const val EXTRA_PERCENT = "EXTRA_PERCENT"

        var isRunning = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONVERT -> {
                val uriStrings = intent.getStringArrayExtra(EXTRA_URIS) ?: return START_NOT_STICKY
                val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: return START_NOT_STICKY
                val kbps = intent.getIntExtra(EXTRA_KBPS, 320)
                startConversion(uriStrings, names, kbps)
            }
            ACTION_CANCEL -> {
                FFmpeg.cancel()
                scope.coroutineContext.cancelChildren()
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startConversion(uriStrings: Array<String>, names: Array<String>, kbps: Int) {
        isRunning = true
        val notification = buildNotification("Przygotowywanie...", 0, uriStrings.size)
        startForeground(NOTIFICATION_ID, notification)

        scope.launch {
            val outputDir = getOutputDir()
            var successCount = 0
            var errorCount = 0

            for (i in uriStrings.indices) {
                if (!isActive) break

                val uri = Uri.parse(uriStrings[i])
                val name = names[i]
                val outputName = name.substringBeforeLast(".") + ".mp3"
                val outputFile = File(outputDir, sanitizeFileName(outputName))

                Log.d(TAG, "Konwersja ${i+1}/${uriStrings.size}: $name -> $outputName ($kbps kbps)")
                sendProgressBroadcast(i + 1, uriStrings.size, name, "converting")
                updateNotification("${i + 1}/${uriStrings.size}: $name", i, uriStrings.size)

                try {
                    // Copy content URI to temp file (mobile-ffmpeg needs file path)
                    val tempInput = copyUriToTempFile(uri, name)
                    val success = convertFile(tempInput.absolutePath, outputFile.absolutePath, kbps)
                    tempInput.delete()

                    if (success) {
                        successCount++
                        Log.d(TAG, "OK: $outputName (${outputFile.length()} bajtow)")
                        sendProgressBroadcast(i + 1, uriStrings.size, name, "file_done")
                    } else {
                        errorCount++
                        sendProgressBroadcast(i + 1, uriStrings.size, name, "error", "Konwersja nieudana")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    errorCount++
                    Log.e(TAG, "Blad: $name: ${e.message}", e)
                    sendProgressBroadcast(i + 1, uriStrings.size, name, "error", e.message)
                }
            }

            withContext(Dispatchers.Main) {
                sendProgressBroadcast(successCount, uriStrings.size, "", "done")
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)

                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val text = if (errorCount > 0) {
                    "Skonwertowano $successCount z ${uriStrings.size} (bledy: $errorCount)"
                } else {
                    "Skonwertowano $successCount z ${uriStrings.size} plikow"
                }
                val doneNotification = NotificationCompat.Builder(this@ConvertService, CHANNEL_ID)
                    .setContentTitle("Konwersja zakonczona")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                    .build()
                nm.notify(NOTIFICATION_ID + 1, doneNotification)
                stopSelf()
            }
        }
    }

    private fun copyUriToTempFile(uri: Uri, name: String): File {
        val ext = name.substringAfterLast(".", "tmp")
        val tempFile = File(cacheDir, "input_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Nie mozna otworzyc pliku")
        return tempFile
    }

    private fun convertFile(inputPath: String, outputPath: String, kbps: Int): Boolean {
        File(outputPath).delete()

        val cmd = arrayOf(
            "-i", inputPath,
            "-vn",
            "-acodec", "libmp3lame",
            "-ab", "${kbps}k",
            "-ar", "44100",
            "-ac", "2",
            "-y", outputPath
        )
        Log.d(TAG, "FFmpeg: ${cmd.joinToString(" ")}")

        val rc = FFmpeg.execute(cmd)
        return if (rc == Config.RETURN_CODE_SUCCESS) {
            true
        } else {
            Log.e(TAG, "FFmpeg failed (rc=$rc)")
            false
        }
    }

    private fun getOutputDir(): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(musicDir, "Converted_MP3")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    private fun sendProgressBroadcast(
        current: Int,
        total: Int,
        fileName: String,
        status: String,
        errorMsg: String? = null,
        percent: Int = 0
    ) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_CURRENT, current)
            putExtra(EXTRA_TOTAL, total)
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_PERCENT, percent)
            errorMsg?.let { putExtra(EXTRA_ERROR_MSG, it) }
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        val cancelIntent = Intent(this, ConvertService::class.java).apply { action = ACTION_CANCEL }
        val cancelPendingIntent = PendingIntent.getService(this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Konwersja do MP3")
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
        val channel = NotificationChannel(CHANNEL_ID, "Konwersja audio", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Powiadomienia o konwersji audio do MP3"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        FFmpeg.cancel()
        scope.cancel()
        isRunning = false
    }
}
