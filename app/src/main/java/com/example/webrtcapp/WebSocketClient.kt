package com.example.webrtcapp

import okhttp3.*
import org.json.JSONObject

class WebSocketClient(
    private val listener: WebSocketListener
) {
    private var webSocket: WebSocket? = null

    fun connect(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun send(message: JSONObject) {
        webSocket?.send(message.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "Closing")
    }
}