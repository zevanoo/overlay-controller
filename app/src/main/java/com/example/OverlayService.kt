package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "OverlayServiceChannel"
        const val NOTIFICATION_ID = 101
        const val ACTION_TERMINATE = "com.example.action.TERMINATE"
        const val PREFS_NAME = "OverlayPrefs"
        const val KEY_SIZE = "pref_size"
        const val KEY_OPACITY = "pref_opacity"
        const val KEY_SENSITIVITY = "pref_sensitivity"

        @Volatile
        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var sharedPreferences: SharedPreferences

    // Overlay View Variables
    private var overlayView: View? = null
    private var maximizedContainer: View? = null
    private var minimizedContainer: View? = null
    private var trackpadArea: View? = null
    private lateinit var overlayParams: WindowManager.LayoutParams

    // Cursor Overlay Variables
    private var pointerView: View? = null
    private var pointerImageView: ImageView? = null
    private lateinit var pointerParams: WindowManager.LayoutParams
    private var isPointerEnabled = false

    // Coordinates of Virtual Mouse Cursor
    private var cursorX = 500f
    private var cursorY = 500f

    // Overlay States & Configuration Values
    private var isMinimized = false
    private var currentScale = 1.0f // 50% to 150%
    private var currentOpacity = 0.8f // 10% to 100%
    private var currentSensitivity = 1.0f // 0.1x to 2.0x

    // Shared Preference Listener
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_SIZE, KEY_OPACITY, KEY_SENSITIVITY -> {
                loadPreferences()
                updateOverlayAppearance()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefListener)

        loadPreferences()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TERMINATE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start Foreground Service with Ongoing Notification
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Show Overlay
        if (overlayView == null) {
            showOverlay()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefListener)
        removeOverlay()
        removePointer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun loadPreferences() {
        // Size: slider progress is 0-100 (represents 50% to 150%)
        val sizeProgress = sharedPreferences.getInt(KEY_SIZE, 50)
        currentScale = 0.5f + (sizeProgress / 100f)

        // Opacity: slider progress is 0-90 (represents 10% to 100%)
        val opacityProgress = sharedPreferences.getInt(KEY_OPACITY, 70)
        currentOpacity = 0.1f + (opacityProgress / 100f)

        // Sensitivity: slider progress is 0-19 (represents 0.1x to 2.0x)
        val sensitivityProgress = sharedPreferences.getInt(KEY_SENSITIVITY, 9)
        currentSensitivity = 0.1f + (sensitivityProgress * 0.1f)
    }

    private fun showOverlay() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            getScaledWidth(),
            getScaledHeight(),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
        maximizedContainer = overlayView?.findViewById(R.id.overlay_maximized_container)
        minimizedContainer = overlayView?.findViewById(R.id.overlay_minimized_container)
        trackpadArea = overlayView?.findViewById(R.id.trackpad_area)

        setupDragListeners()
        setupButtonListeners()
        updateOverlayAppearance()

        windowManager.addView(overlayView, overlayParams)

        // Set cursor default to screen center
        val dm = resources.displayMetrics
        cursorX = (dm.widthPixels / 2).toFloat()
        cursorY = (dm.heightPixels / 2).toFloat()
    }

    private fun updateOverlayAppearance() {
        val view = overlayView ?: return

        // Update Opacity
        view.alpha = currentOpacity

        // Update Dimensions if Maximized
        if (!isMinimized) {
            overlayParams.width = getScaledWidth()
            overlayParams.height = getScaledHeight()
            try {
                windowManager.updateViewLayout(view, overlayParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getScaledWidth(): Int {
        val baseWidthDp = 260
        val density = resources.displayMetrics.density
        val baseWidthPx = (baseWidthDp * density).toInt()
        return (baseWidthPx * currentScale).toInt()
    }

    private fun getScaledHeight(): Int {
        val baseHeightDp = 320
        val density = resources.displayMetrics.density
        val baseHeightPx = (baseHeightDp * density).toInt()
        return (baseHeightPx * currentScale).toInt()
    }

    private fun setupDragListeners() {
        val view = overlayView ?: return
        val dragBar = view.findViewById<View>(R.id.header_drag_bar)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val controllerTouchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams.x
                    initialY = overlayParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    overlayParams.x = initialX + dx.toInt()
                    overlayParams.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(view, overlayParams)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                else -> false
            }
        }

        dragBar.setOnTouchListener(controllerTouchListener)

        // Make the minimized floating circle draggable too!
        val minimizedTouchListener = object : View.OnTouchListener {
            private var dragTriggered = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = overlayParams.x
                        initialY = overlayParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        dragTriggered = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            dragTriggered = true
                        }
                        overlayParams.x = initialX + dx.toInt()
                        overlayParams.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(view, overlayParams)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragTriggered) {
                            // User clicked the minimized circle -> Maximize!
                            maximizeOverlay()
                        }
                        return true
                    }
                }
                return false
            }
        }

        minimizedContainer?.setOnTouchListener(minimizedTouchListener)
    }

    private fun setupButtonListeners() {
        val view = overlayView ?: return

        // Minimize Button
        view.findViewById<View>(R.id.btn_minimize).setOnClickListener {
            minimizeOverlay()
        }

        // Action Buttons
        val btnShift = view.findViewById<Button>(R.id.btn_shift)
        val btnMouseToggle = view.findViewById<Button>(R.id.btn_mouse_toggle)
        val btnLClick = view.findViewById<Button>(R.id.btn_lclick)
        val btnRClick = view.findViewById<Button>(R.id.btn_rclick)
        val btnShLClick = view.findViewById<Button>(R.id.btn_sh_lclick)
        val btnShRClick = view.findViewById<Button>(R.id.btn_sh_rclick)

        btnShift.setOnClickListener {
            handleAction("SHIFT")
        }

        btnLClick.setOnClickListener {
            handleAction("LEFT_CLICK")
        }

        btnRClick.setOnClickListener {
            handleAction("RIGHT_CLICK")
        }

        btnShLClick.setOnClickListener {
            handleAction("SHIFT_LEFT_CLICK")
        }

        btnShRClick.setOnClickListener {
            handleAction("SHIFT_RIGHT_CLICK")
        }

        // Toggle Mouse Pointer
        btnMouseToggle.setOnClickListener {
            isPointerEnabled = !isPointerEnabled
            if (isPointerEnabled) {
                btnMouseToggle.text = "POINTER: ON"
                showPointer()
            } else {
                btnMouseToggle.text = "POINTER: OFF"
                removePointer()
            }
        }

        // Trackpad Touch Movement
        var trackpadStartX = 0f
        var trackpadStartY = 0f

        trackpadArea?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    trackpadStartX = event.x
                    trackpadStartY = event.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - trackpadStartX
                    val dy = event.y - trackpadStartY

                    // Update pointer location based on movement and sensitivity
                    cursorX += dx * currentSensitivity
                    cursorY += dy * currentSensitivity

                    // Constrain within screen dimensions
                    val dm = resources.displayMetrics
                    if (cursorX < 0) cursorX = 0f
                    if (cursorX > dm.widthPixels) cursorX = dm.widthPixels.toFloat()
                    if (cursorY < 0) cursorY = 0f
                    if (cursorY > dm.heightPixels) cursorY = dm.heightPixels.toFloat()

                    updatePointerPosition()

                    // Reset trackpad baseline to continuous dragging feel
                    trackpadStartX = event.x
                    trackpadStartY = event.y
                    true
                }
                else -> false
            }
        }
    }

    private fun minimizeOverlay() {
        isMinimized = true
        maximizedContainer?.visibility = View.GONE
        minimizedContainer?.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        overlayParams.width = (54 * density).toInt()
        overlayParams.height = (54 * density).toInt()

        try {
            windowManager.updateViewLayout(overlayView, overlayParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun maximizeOverlay() {
        isMinimized = false
        minimizedContainer?.visibility = View.GONE
        maximizedContainer?.visibility = View.VISIBLE

        overlayParams.width = getScaledWidth()
        overlayParams.height = getScaledHeight()

        try {
            windowManager.updateViewLayout(overlayView, overlayParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showPointer() {
        if (pointerView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Layout parameters for Cursor are completely click-through (FLAG_NOT_TOUCHABLE and FLAG_NOT_FOCUSABLE)
        pointerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cursorX.toInt()
            y = cursorY.toInt()
        }

        pointerImageView = ImageView(this).apply {
            setImageResource(R.drawable.ic_cursor)
        }

        pointerView = pointerImageView
        windowManager.addView(pointerView, pointerParams)
    }

    private fun removePointer() {
        pointerView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            pointerView = null
            pointerImageView = null
        }
    }

    private fun updatePointerPosition() {
        val view = pointerView ?: return
        
        // Dynamically clamp cursor to current screen configuration (auto-scales with Portrait/Landscape)
        val dm = resources.displayMetrics
        if (cursorX < 0) cursorX = 0f
        if (cursorX > dm.widthPixels) cursorX = dm.widthPixels.toFloat()
        if (cursorY < 0) cursorY = 0f
        if (cursorY > dm.heightPixels) cursorY = dm.heightPixels.toFloat()

        pointerParams.x = cursorX.toInt()
        pointerParams.y = cursorY.toInt()
        try {
            windowManager.updateViewLayout(view, pointerParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleAction(actionType: String) {
        val dm = resources.displayMetrics
        // Ensure coordinate sanity before dispatching gestures
        if (cursorX < 0) cursorX = 0f
        if (cursorX > dm.widthPixels) cursorX = dm.widthPixels.toFloat()
        if (cursorY < 0) cursorY = 0f
        if (cursorY > dm.heightPixels) cursorY = dm.heightPixels.toFloat()

        val accessService = OverlayAccessibilityService.instance
        if (accessService != null) {
            when (actionType) {
                "SHIFT" -> {
                    accessService.dispatchKey(android.view.KeyEvent.KEYCODE_SHIFT_LEFT)
                    Toast.makeText(this, "Accessibility: SHIFT Input", Toast.LENGTH_SHORT).show()
                }
                "LEFT_CLICK" -> {
                    accessService.dispatchClick(cursorX, cursorY)
                    Toast.makeText(this, "Accessibility: Left Click at (${cursorX.toInt()}, ${cursorY.toInt()})", Toast.LENGTH_SHORT).show()
                }
                "RIGHT_CLICK" -> {
                    accessService.dispatchClick(cursorX, cursorY)
                    Toast.makeText(this, "Accessibility: Right Click at (${cursorX.toInt()}, ${cursorY.toInt()})", Toast.LENGTH_SHORT).show()
                }
                "SHIFT_LEFT_CLICK" -> {
                    accessService.dispatchShiftClick(cursorX, cursorY, false)
                    Toast.makeText(this, "Accessibility: Shift + Left Click at (${cursorX.toInt()}, ${cursorY.toInt()})", Toast.LENGTH_SHORT).show()
                }
                "SHIFT_RIGHT_CLICK" -> {
                    accessService.dispatchShiftClick(cursorX, cursorY, true)
                    Toast.makeText(this, "Accessibility: Shift + Right Click at (${cursorX.toInt()}, ${cursorY.toInt()})", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Enable Accessibility Permission! ($actionType)", Toast.LENGTH_LONG).show()
        }

        // Mouse click feedback animation
        val view = pointerView ?: return
        view.post {
            view.alpha = 0.4f
            view.postDelayed({
                view.alpha = 1.0f
            }, 150)
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Overlay Controller Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val terminateIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_TERMINATE
        }
        
        // Use FLAG_IMMUTABLE as per modern Android standards
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val terminatePendingIntent = PendingIntent.getService(this, 0, terminateIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MCO Active")
            .setContentText("Minecraft Bedrock Overlay is active.")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Standard system icon
            .setOngoing(true) // Prevent swipe dismissal
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Terminate", terminatePendingIntent)
            .build()
    }
}
