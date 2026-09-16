package com.mirrorremote.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.MotionEvent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.security.SecureRandom

class MainActivity : AppCompatActivity(), RelayClient.Listener {
    private lateinit var status:TextView; private lateinit var deviceText:TextView; private lateinit var pinText:TextView
    private lateinit var targetId:EditText; private lateinit var targetPin:EditText; private lateinit var remoteImage:ImageView
    private lateinit var relay:RelayClient
    private lateinit var deviceId:String; private lateinit var pin:String
    private var downX=0f; private var downY=0f

    private val captureLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK && result.data!=null) {
            val i=Intent(this,ScreenCaptureService::class.java).putExtra(ScreenCaptureService.EXTRA_RESULT_CODE,result.resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA,result.data)
            startForegroundService(i)
            status.text="Session active — sharing Android screen"
        } else { status.text="Screen sharing permission denied"; endSession() }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        status=findViewById(R.id.statusText); deviceText=findViewById(R.id.deviceIdText); pinText=findViewById(R.id.pinText)
        targetId=findViewById(R.id.targetId); targetPin=findViewById(R.id.targetPin); remoteImage=findViewById(R.id.remoteImage)
        val prefs=getSharedPreferences("identity",MODE_PRIVATE)
        deviceId=prefs.getString("deviceId",null) ?: (100000000+SecureRandom().nextInt(900000000)).toString().also{prefs.edit().putString("deviceId",it).apply()}
        pin=prefs.getString("pin",null) ?: (100000+SecureRandom().nextInt(900000)).toString().also{prefs.edit().putString("pin",it).apply()}
        deviceText.text="Device ID: $deviceId"; pinText.text="Session PIN: $pin"

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.connectButton).setOnClickListener {
            if(targetId.text.length!=9 || targetPin.text.length!=6) Toast.makeText(this,"Enter a 9-digit ID and 6-digit PIN",Toast.LENGTH_SHORT).show()
            else relay.send(JSONObject().put("type","connect_request").put("targetId",targetId.text.toString()).put("pin",targetPin.text.toString())).also{status.text="Waiting for remote approval…"}
        }
        findViewById<Button>(R.id.endButton).setOnClickListener { endSession() }
        remoteImage.setOnTouchListener { v,e ->
            if(SessionState.role!="controller" || SessionState.sessionId==null) return@setOnTouchListener true
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {downX=e.x;downY=e.y;true}
                MotionEvent.ACTION_UP -> {
                    val w=v.width.toDouble().coerceAtLeast(1.0); val h=v.height.toDouble().coerceAtLeast(1.0)
                    val x1=(downX/w).coerceIn(0.0,1.0); val y1=(downY/h).coerceIn(0.0,1.0); val x2=(e.x/w).coerceIn(0.0,1.0); val y2=(e.y/h).coerceIn(0.0,1.0)
                    val o=JSONObject().put("type","input").put("sessionId",SessionState.sessionId)
                    if(kotlin.math.abs(x2-x1)+kotlin.math.abs(y2-y1)<0.02) o.put("action","tap").put("x",x2).put("y",y2)
                    else o.put("action","swipe").put("x1",x1).put("y1",y1).put("x2",x2).put("y2",y2).put("duration",300)
                    relay.send(o); true
                }
                else -> true
            }
        }
        relay=RelayClient(getString(R.string.relay_url),deviceId,pin,this); SessionState.relay=relay; relay.connect()
    }

    override fun onStatus(text:String)=runOnUiThread{status.text=text}
    override fun onRelayMessage(o:JSONObject)=runOnUiThread {
        when(o.optString("type")) {
            "incoming_request" -> {
                val sid=o.getString("sessionId")
                AlertDialog.Builder(this).setTitle("Remote control request")
                    .setMessage("${o.optString("controllerName")} (${o.optString("controllerId")}) wants to control this phone. Allow?")
                    .setNegativeButton("Reject") { _,_->relay.send(JSONObject().put("type","reject").put("sessionId",sid))}
                    .setPositiveButton("Accept") { _,_-> relay.send(JSONObject().put("type","accept").put("sessionId",sid)) }.show()
            }
            "session_ready" -> {
                SessionState.sessionId=o.getString("sessionId"); SessionState.role=o.getString("role")
                status.text="Session active — ${SessionState.role}"
                if(SessionState.role=="host") {
                    val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    captureLauncher.launch(mgr.createScreenCaptureIntent())
                }
            }
            "frame" -> if(SessionState.role=="controller") {
                try { val b=Base64.decode(o.getString("data"),Base64.DEFAULT); remoteImage.setImageBitmap(BitmapFactory.decodeByteArray(b,0,b.size)) } catch (_:Exception){}
            }
            "input" -> if(SessionState.role=="host") {
                val svc=RemoteAccessibilityService.instance ?: return@runOnUiThread
                when(o.optString("action")) {
                    "tap" -> svc.tap(o.getDouble("x"),o.getDouble("y"))
                    "swipe" -> svc.swipe(o.getDouble("x1"),o.getDouble("y1"),o.getDouble("x2"),o.getDouble("y2"),o.optLong("duration",300))
                }
            }
            "connect_failed" -> status.text="Connection failed: ${o.optString("reason")}"
            "session_rejected" -> status.text="Remote user rejected the request"
            "session_end" -> resetSession("Session ended")
        }
    }

    private fun endSession(){ SessionState.sessionId?.let{relay.send(JSONObject().put("type","session_end").put("sessionId",it))}; resetSession("Online") }
    private fun resetSession(text:String){ stopService(Intent(this,ScreenCaptureService::class.java)); SessionState.sessionId=null;SessionState.role=null;remoteImage.setImageDrawable(null);status.text=text }
    override fun onDestroy(){ relay.close(); super.onDestroy() }
}
