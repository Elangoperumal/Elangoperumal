package com.mirrorremote.app

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RelayClient(
    private val url: String,
    private val deviceId: String,
    private val pin: String,
    private val listener: Listener
) {
    interface Listener { fun onRelayMessage(o: JSONObject); fun onStatus(text: String) }
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null

    fun connect() {
        val req = Request.Builder().url(url).build()
        socket = client.newWebSocket(req, object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(JSONObject().put("type","register").put("deviceId",deviceId).put("pin",pin)
                    .put("deviceName", android.os.Build.MODEL).put("platform","android"))
                listener.onStatus("Online via $url")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { listener.onRelayMessage(JSONObject(text)) } catch (_: Exception) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onStatus("Relay error: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onStatus("Relay disconnected")
            }
        })
    }

    fun send(o: JSONObject) { socket?.send(o.toString()) }
    fun close() { socket?.close(1000,"bye") }
}
