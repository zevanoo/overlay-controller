package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1234
        private const val REQUEST_NOTIFICATION_PERMISSION = 5678
    }

    private lateinit var sharedPreferences: SharedPreferences

    // UI Components
    private lateinit var txtStatus: TextView
    private lateinit var indicatorStatus: View
    private lateinit var seekbarSize: SeekBar
    private lateinit var seekbarOpacity: SeekBar
    private lateinit var seekbarSensitivity: SeekBar
    private lateinit var txtSizeVal: TextView
    private lateinit var txtOpacityVal: TextView
    private lateinit var txtSensitivityVal: TextView
    private lateinit var btnEnable: Button
    private lateinit var btnDisable: Button

    // State Poller Handler
    private val handler = Handler(Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            updateStatusUI()
            handler.postDelayed(this, 1000) // Poll every second to keep status in sync
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)

        initializeViews()
        loadSavedSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
        handler.post(statusPoller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoller)
    }

    private fun initializeViews() {
        txtStatus = findViewById(R.id.txt_status)
        indicatorStatus = findViewById(R.id.indicator_status)
        seekbarSize = findViewById(R.id.seekbar_size)
        seekbarOpacity = findViewById(R.id.seekbar_opacity)
        seekbarSensitivity = findViewById(R.id.seekbar_sensitivity)
        txtSizeVal = findViewById(R.id.txt_size_val)
        txtOpacityVal = findViewById(R.id.txt_opacity_val)
        txtSensitivityVal = findViewById(R.id.txt_sensitivity_val)
        btnEnable = findViewById(R.id.btn_enable)
        btnDisable = findViewById(R.id.btn_disable)
    }

    private fun loadSavedSettings() {
        // Overlay Size: progress 0-100 represents 50% to 150% (Default 50 maps to 100%)
        val sizeProgress = sharedPreferences.getInt(OverlayService.KEY_SIZE, 50)
        seekbarSize.progress = sizeProgress
        updateSizeValueText(sizeProgress)

        // Opacity: progress 0-90 represents 10% to 100% (Default 70 maps to 80%)
        val opacityProgress = sharedPreferences.getInt(OverlayService.KEY_OPACITY, 70)
        seekbarOpacity.progress = opacityProgress
        updateOpacityValueText(opacityProgress)

        // Mouse Sensitivity: progress 0-19 represents 0.1x to 2.0x (Default 9 maps to 1.0x)
        val sensitivityProgress = sharedPreferences.getInt(OverlayService.KEY_SENSITIVITY, 9)
        seekbarSensitivity.progress = sensitivityProgress
        updateSensitivityValueText(sensitivityProgress)
    }

    private fun setupListeners() {
        // Size Seekbar Listener
        seekbarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSizeValueText(progress)
                if (fromUser) {
                    sharedPreferences.edit().putInt(OverlayService.KEY_SIZE, progress).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Opacity Seekbar Listener
        seekbarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateOpacityValueText(progress)
                if (fromUser) {
                    sharedPreferences.edit().putInt(OverlayService.KEY_OPACITY, progress).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Sensitivity Seekbar Listener
        seekbarSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSensitivityValueText(progress)
                if (fromUser) {
                    sharedPreferences.edit().putInt(OverlayService.KEY_SENSITIVITY, progress).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Activate Button
        btnEnable.setOnClickListener {
            if (checkAndRequestPermissions()) {
                startOverlayService()
            }
        }

        // Deactivate Button
        btnDisable.setOnClickListener {
            stopOverlayService()
        }
    }

    private fun updateSizeValueText(progress: Int) {
        val percentage = 50 + progress
        txtSizeVal.text = "$percentage%"
    }

    private fun updateOpacityValueText(progress: Int) {
        val percentage = 10 + progress
        txtOpacityVal.text = "$percentage%"
    }

    private fun updateSensitivityValueText(progress: Int) {
        val value = 0.1f + (progress * 0.1f)
        txtSensitivityVal.text = String.format("%.1fx", value)
    }

    private fun updateStatusUI() {
        if (OverlayService.isRunning) {
            txtStatus.text = "ACTIVE"
            txtStatus.setTextColor(0xFFFFFFFF.toInt()) // Monochrome pure white for active status
            indicatorStatus.setBackgroundColor(0xFFFFFFFF.toInt())
        } else {
            txtStatus.text = "INACTIVE"
            txtStatus.setTextColor(0xFF888888.toInt()) // Monochrome muted gray for inactive status
            indicatorStatus.setBackgroundColor(0xFF444444.toInt())
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = android.content.ComponentName(this, OverlayAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun checkAndRequestPermissions(): Boolean {
        // 1. Check Draw over other apps permission (System Alert Window)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable 'Draw over other apps' permission first.", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            return false
        }

        // 2. Check Android 13+ Notification Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
                return false
            }
        }

        // 3. Check Accessibility Service Permission
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable 'Overlay Controller Input Service' in Accessibility settings.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return false
        }

        return true
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateStatusUI()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
        updateStatusUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
                // Proceed with starting if notification permission is also met or not required
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || 
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    startOverlayService()
                }
            } else {
                Toast.makeText(this, "Failed to enable overlay: Permission denied.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
                if (Settings.canDrawOverlays(this)) {
                    startOverlayService()
                }
            } else {
                Toast.makeText(this, "Notification denied. Service runs but foreground notification might not be visible.", Toast.LENGTH_LONG).show()
                // Some Android versions will block foreground services without notification permission, 
                // but we can attempt to start it.
                if (Settings.canDrawOverlays(this)) {
                    startOverlayService()
                }
            }
        }
    }
}
