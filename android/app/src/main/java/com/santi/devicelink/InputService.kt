package com.santi.devicelink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService.StrokeDescription
import android.widget.Toast

class InputService : AccessibilityService() {

    companion object { @Volatile var inst: InputService? = null }

    override fun onServiceConnected() { inst = this }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun tap(x: Float, y: Float, dur: Long = 70) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(StrokeDescription(p, 0, dur.coerceIn(40, 1500))).build()
        dispatchGesture(g, null, null)
    }

    fun stroke(points: List<FloatArray>, totalMs: Long) {
        if (points.isEmpty()) return
        if (points.size == 1) { tap(points[0][0], points[0][1]); return }
        val p = Path().apply {
            moveTo(points[0][0], points[0][1])
            for (i in 1 until points.size) lineTo(points[i][0], points[i][1])
        }
        val dur = totalMs.coerceIn(80, 3000)
        val g = GestureDescription.Builder().addStroke(StrokeDescription(p, 0, dur)).build()
        dispatchGesture(g, null, null)
    }

    fun global(a: Int) { performGlobalAction(a) }

    fun typeText(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dl", s))
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val ok = node != null && node.isEditable &&
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (!ok) mainToast("Text clipboard pe copy — phone pe long-press karke PASTE karo")
    }

    fun mainToast(s: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
    }
}
