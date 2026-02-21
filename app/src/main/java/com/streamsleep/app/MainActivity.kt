package com.streamsleep.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var spinnerApp: Spinner
    private lateinit var spinnerTime: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private val streamingApps = listOf(
        StreamingApp("Netflix", "com.netflix.mediaclient"),
        StreamingApp("Prime Video", "com.amazon.avod.thirdpartyclient"),
        StreamingApp("Disney+", "com.disney.disneyplus")
    )

    private val timeOptions = listOf(1, 15, 30, 45, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerApp = findViewById(R.id.spinnerApp)
        spinnerTime = findViewById(R.id.spinnerTime)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        setupSpinners()
        setupButtons()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupSpinners() {
        val appNames = streamingApps.map { it.name }
        spinnerApp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appNames)

        val timeLabels = timeOptions.map { if (it == 1) "1 minuta" else "$it minut" }
        spinnerTime.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, timeLabels)
    }

    private fun setupButtons() {
        btnStart.setOnClickListener {
            if (!hasAccessibilityPermission()) {
                showAccessibilityDialog()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                showOverlayDialog()
                return@setOnClickListener
            }

            val selectedApp = streamingApps[spinnerApp.selectedItemPosition]
            val selectedMinutes = timeOptions[spinnerTime.selectedItemPosition]

            val intent = Intent(this, SleepTimerService::class.java).apply {
                action = SleepTimerService.ACTION_START
                putExtra(SleepTimerService.EXTRA_APP_PACKAGE, selectedApp.packageName)
                putExtra(SleepTimerService.EXTRA_APP_NAME, selectedApp.name)
                putExtra(SleepTimerService.EXTRA_MINUTES, selectedMinutes)
            }
            startForegroundService(intent)
            tvStatus.text = "⏱ Timer uruchomiony: ${selectedApp.name} zostanie zamknięty za $selectedMinutes min"
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, SleepTimerService::class.java).apply {
                action = SleepTimerService.ACTION_STOP
            }
            startService(intent)
            tvStatus.text = "Timer zatrzymany"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    private fun updateStatus() {
        val isRunning = SleepTimerService.isRunning
        btnStart.isEnabled = !isRunning
        btnStop.isEnabled = isRunning
        if (!isRunning && tvStatus.text.startsWith("⏱")) {
            tvStatus.text = "Wybierz aplikację i czas"
        }
    }

    private fun hasAccessibilityPermission(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Wymagane uprawnienie")
            .setMessage("StreamSleep potrzebuje dostępu do Usług ułatwień dostępu, aby móc zamykać aplikacje.\n\nPrzejdź do Ustawienia → Ułatwienia dostępu → Zainstalowane aplikacje → StreamSleep → Włącz")
            .setPositiveButton("Otwórz ustawienia") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun showOverlayDialog() {
        AlertDialog.Builder(this)
            .setTitle("Wymagane uprawnienie")
            .setMessage("StreamSleep potrzebuje uprawnienia do wyświetlania na wierzchu innych aplikacji (timer countdown).")
            .setPositiveButton("Zezwól") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    data class StreamingApp(val name: String, val packageName: String)
}
