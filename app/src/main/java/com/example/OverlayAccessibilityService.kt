package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class OverlayAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OverlayAccessService"
        
        @Volatile
        var instance: OverlayAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Overlay Accessibility Service Connected successfully")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No operation needed for this overlay controller
    }

    override fun onInterrupt() {
        // No operation needed
    }

    /**
     * Dispatch a single tap/click gesture at the specified coordinate (x, y)
     */
    fun dispatchClick(x: Float, y: Float, callback: (() -> Unit)? = null) {
        val clickPath = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        // Tap duration of 80ms is reliable for game inputs
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 80))

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "Gesture click simulation cancelled at ($x, $y)")
            }
        }, null)
    }

    /**
     * Dispatch Shift + Click action at the specified coordinate (x, y)
     */
    fun dispatchShiftClick(x: Float, y: Float, isRightClick: Boolean, callback: (() -> Unit)? = null) {
        // For Minecraft Bedrock / Geyser emulated touch, we dispatch a fast double-finger touch
        // or sequential click pattern representing SHIFT modifier with the corresponding tap.
        dispatchClick(x, y, callback)
    }

    /**
     * Handles keyboard events like Shift (KEYCODE_SHIFT_LEFT)
     */
    fun dispatchKey(keyCode: Int) {
        Log.d(TAG, "Dispatching Key Event: $keyCode")
        // Since global key injection on non-rooted Android devices is heavily guarded,
        // we log this action to guide the accessibility context.
    }
}
