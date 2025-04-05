package com.example.webrtcapp

import android.util.Log
import okhttp3.*
import org.json.JSONObject
class WebSocketClient(
    private val listener: WebSocketListener
) {
    private var webSocket: WebSocket? = null

    fun connect(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebRTCApp", "WebSocket connected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebRTCApp", "WebSocket connection failed: ${t.message}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebRTCApp", "WebSocket message received: $text")
                listener.onMessage(webSocket, text)
            }
        })
    }

    fun send(message: JSONObject) {
        Log.d("WebRTCApp", "WebSocket sending message: $message")
        webSocket?.send(message.toString())
    }

    fun disconnect() {
        Log.d("WebRTCApp", "WebSocket disconnecting")
        webSocket?.close(1000, "Closing")
    }
}
