package com.mirrorremote.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: RemoteAccessibilityService? = null }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(nx: Double, ny: Double) {
        val dm = resources.displayMetrics
        val p = Path().apply { moveTo((nx*dm.widthPixels).toFloat(), (ny*dm.heightPixels).toFloat()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,70)).build(), null, null)
    }
    fun swipe(x1:Double,y1:Double,x2:Double,y2:Double,duration:Long) {
        val dm=resources.displayMetrics
        val p=Path().apply { moveTo((x1*dm.widthPixels).toFloat(),(y1*dm.heightPixels).toFloat()); lineTo((x2*dm.widthPixels).toFloat(),(y2*dm.heightPixels).toFloat()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,duration.coerceIn(80,2000))).build(),null,null)
    }
}
