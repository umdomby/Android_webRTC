package com.example.webrtcapp

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.SdpObserver
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.webrtc.*

class MainActivity : ComponentActivity() {
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var webSocketClient: WebSocketClient
    private var localVideoView: SurfaceViewRenderer? = null
    private var remoteVideoView: SurfaceViewRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("WebRTCApp", "onCreate started")

        // Запрос разрешений на камеру и микрофон
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d("WebRTCApp", "Requesting permissions")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                1
            )
        }

        setContent {
            WebRTCAppTheme {
                var username by remember { mutableStateOf("User${(1000..9999).random()}") }
                var room by remember { mutableStateOf("room1") }
                var isConnected by remember { mutableStateOf(false) }
                var isCallActive by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf("") }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    TextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = room,
                        onValueChange = { room = it },
                        label = { Text("Room") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            Log.d("WebRTCApp", "Connect button clicked")
                            connectToRoom(username, room)
                            isConnected = true
                        },
                        enabled = !isConnected
                    ) {
                        Text(if (isConnected) "Connected" else "Connect")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            Log.d("WebRTCApp", "Start call button clicked")
                            startCall()
                            isCallActive = true
                        },
                        enabled = isConnected && !isCallActive
                    ) {
                        Text(if (isCallActive) "Call Active" else "Start Call")
                    }

                    if (error.isNotEmpty()) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(8.dp)
                    ) {
                        AndroidView(
                            factory = { context ->
                                Log.d("WebRTCApp", "Creating local video view")
                                SurfaceViewRenderer(context).also {
                                    it.init(EglBase.create().eglBaseContext, null)
                                    it.setMirror(true) // Зеркальное отображение для фронтальной камеры
                                    localVideoView = it
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )

                        AndroidView(
                            factory = { context ->
                                Log.d("WebRTCApp", "Creating remote video view")
                                SurfaceViewRenderer(context).also {
                                    it.init(EglBase.create().eglBaseContext, null)
                                    remoteVideoView = it
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("WebRTCApp", "Permissions granted")
                // Повторная инициализация после получения разрешений
                localVideoView?.let {
                    webRTCClient.createLocalStream(it)
                }
            } else {
                Log.e("WebRTCApp", "Permissions denied")
                // Показать сообщение пользователю
            }
        }
    }

    private fun connectToRoom(username: String, room: String) {
        Log.d("WebRTCApp", "Connecting to room: $room as $username")

        // Инициализация WebRTCClient
        webRTCClient = WebRTCClient(
            context = this,
            observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    Log.d("WebRTCApp", "onIceCandidate: ${candidate?.sdpMid}")
                    candidate?.let {
                        val message = JSONObject().apply {
                            put("type", "ice_candidate")
                            put("ice", JSONObject().apply {
                                put("sdpMid", it.sdpMid)
                                put("sdpMLineIndex", it.sdpMLineIndex)
                                put("sdp", it.sdp)
                            })
                        }
                        webSocketClient.send(message)
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                    Log.d("WebRTCApp", "onIceCandidatesRemoved: ${candidates?.size} candidates")
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onAddStream(stream: MediaStream?) {
                    Log.d("WebRTCApp", "onAddStream: Remote stream added")
                    stream?.videoTracks?.firstOrNull()?.addSink(remoteVideoView)
                }
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )

        // Создание локального потока
        localVideoView?.let {
            Log.d("WebRTCApp", "Creating local stream")
            webRTCClient.createLocalStream(it)
        }

        // Инициализация WebSocketClient
        webSocketClient = WebSocketClient(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebRTCApp", "Received message: $text")
                val message = JSONObject(text)
                when (message.getString("type")) {
                    "offer" -> {
                        val sdp = SessionDescription(
                            SessionDescription.Type.OFFER,
                            message.getString("sdp")
                        )
                        webRTCClient.setRemoteDescription(sdp, object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription?) {}
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(error: String?) {
                                Log.e("WebRTCApp", "Failed to create remote description: $error")
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e("WebRTCApp", "Failed to set remote description: $error")
                            }
                        })
                    }
                    "ice_candidate" -> {
                        val ice = message.getJSONObject("ice")
                        val candidate = IceCandidate(
                            ice.getString("sdpMid"),
                            ice.getInt("sdpMLineIndex"),
                            ice.getString("sdp")
                        )
                        webRTCClient.addIceCandidate(candidate)
                    }
                }
            }
        })

        // Подключение к WebSocket
        Log.d("WebRTCApp", "Connecting WebSocket")
        webSocketClient.connect("wss://anybet.site/ws")

        // Отправка сообщения о присоединении к комнате
        val joinMessage = JSONObject().apply {
            put("type", "join")
            put("username", username)
            put("room", room)
        }
        Log.d("WebRTCApp", "Sending join message")
        webSocketClient.send(joinMessage)
    }

    private fun startCall() {
        Log.d("WebRTCApp", "Starting call")
        webRTCClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d("WebRTCApp", "Offer created")
                desc?.let {
                    val message = JSONObject().apply {
                        put("type", "offer")
                        put("sdp", it.description)
                    }
                    webSocketClient.send(message)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e("WebRTCApp", "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTCApp", "Failed to set offer: $error")
            }
        })
    }

    override fun onDestroy() {
        Log.d("WebRTCApp", "onDestroy")
        if (::webSocketClient.isInitialized) {
            webSocketClient.disconnect()
        }
        if (::webRTCClient.isInitialized) {
            webRTCClient.close()
        }
        super.onDestroy()
    }
}

@Composable
fun WebRTCAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        content = content
    )
}
