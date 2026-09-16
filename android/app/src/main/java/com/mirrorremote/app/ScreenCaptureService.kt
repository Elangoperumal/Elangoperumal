package com.mirrorremote.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE="resultCode"
        const val EXTRA_RESULT_DATA="resultData"
        const val CHANNEL="mirror_remote_capture"
    }
    private var projection: MediaProjection?=null
    private var reader: ImageReader?=null
    private val busy=AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch=NotificationChannel(CHANNEL,"Remote screen sharing",NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n=Notification.Builder(this,CHANNEL).setContentTitle("Mirror Remote")
            .setContentText("Your screen is being shared in an approved session")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).build()
        startForeground(101,n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code=intent?.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if(code!=Activity.RESULT_OK || data==null){ stopSelf(); return START_NOT_STICKY }
        val mgr=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection=mgr.getMediaProjection(code,data)
        projection?.registerCallback(object:MediaProjection.Callback(){ override fun onStop(){ cleanup() } },null)

        val dm=resources.displayMetrics
        val srcW=dm.widthPixels; val srcH=dm.heightPixels
        val maxW=720
        val scale=minOf(1.0,maxW.toDouble()/srcW)
        val w=(srcW*scale).toInt().coerceAtLeast(2); val h=(srcH*scale).toInt().coerceAtLeast(2)
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
        projection?.createVirtualDisplay("MirrorRemote",w,h,dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader?.surface,null,null)
        reader?.setOnImageAvailableListener({ r ->
            if(!busy.compareAndSet(false,true)) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            val image=r.acquireLatestImage()
            if(image==null){busy.set(false);return@setOnImageAvailableListener}
            try {
                val plane=image.planes[0]; val buf=plane.buffer
                val pixelStride=plane.pixelStride; val rowStride=plane.rowStride
                val rowPadding=rowStride-pixelStride*w
                val tmp=Bitmap.createBitmap(w+rowPadding/pixelStride,h,Bitmap.Config.ARGB_8888)
                tmp.copyPixelsFromBuffer(buf)
                val bmp=Bitmap.createBitmap(tmp,0,0,w,h); tmp.recycle()
                val out=ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG,45,out); bmp.recycle()
                val sid=SessionState.sessionId
                if(sid!=null && SessionState.role=="host") {
                    SessionState.relay?.send(JSONObject().put("type","frame").put("sessionId",sid).put("width",w).put("height",h)
                        .put("data",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)))
                }
            } catch (_:Exception) {} finally { image.close(); busy.set(false) }
        },null)
        return START_NOT_STICKY
    }

    private fun cleanup(){ reader?.close(); reader=null; projection=null; stopSelf() }
    override fun onDestroy(){ projection?.stop(); cleanup(); super.onDestroy() }
}
