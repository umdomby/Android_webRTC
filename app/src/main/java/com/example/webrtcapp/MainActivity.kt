package com.example.webrtcapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.*
import com.example.webrtcapp.WebSocketListener


class MainActivity : ComponentActivity() {


    private lateinit var webRTCClient: WebRTCClient
    private lateinit var webSocketClient: WebSocketClient
    private var remoteVideoView: SurfaceViewRenderer? = null
    private val eglBase = EglBase.create()

    // Добавленная функция проверки разрешения камеры
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            if (checkCameraPermission()) {
                initializeWebRTC()
            } else {
                Log.e("WebRTCApp", "Camera permission not granted")
            }
        } else {
            Log.e("WebRTCApp", "Permissions not granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requiredPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        if (requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            initializeWebRTC()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
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
                            .height(300.dp)
                            .padding(8.dp)
                    ) {
                        AndroidView(
                            factory = { context ->
                                SurfaceViewRenderer(context).also { view ->
                                    view.init(eglBase.eglBaseContext, null)
                                    view.setMirror(true)
                                    view.setEnableHardwareScaler(true)
                                    webRTCClient.createLocalStream(view)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )

                        AndroidView(
                            factory = { context ->
                                SurfaceViewRenderer(context).also { view ->
                                    view.init(eglBase.eglBaseContext, null)
                                    view.setEnableHardwareScaler(true)
                                    remoteVideoView = view
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    private fun initializeWebRTC() {
        webRTCClient = WebRTCClient(
            context = this,
            observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
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

                override fun onAddStream(stream: MediaStream?) {
                    Log.d("WebRTCApp", "onAddStream: ${stream?.id}")
                    stream?.videoTracks?.forEach { track ->
                        Log.d("WebRTCApp", "Video track: ${track.id()}, enabled: ${track.enabled()}")
                        track.addSink(remoteVideoView)
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )

        webSocketClient = WebSocketClient(object : WebSocketListener {
            override fun onMessage(message: JSONObject) {
                runOnUiThread {
                    try {
                        if (message.has("type")) {
                            when (message.getString("type")) {
                                "offer" -> handleOffer(message)
                                "answer" -> handleAnswer(message)
                                "ice_candidate" -> handleIceCandidate(message)
                                else -> Log.w("WebRTCApp", "Unknown message type: ${message.getString("type")}")
                            }
                        } else {
                            Log.w("WebRTCApp", "Received message without type: $message")
                        }
                    } catch (e: Exception) {
                        Log.e("WebRTCApp", "Error processing message: ${e.message}")
                    }
                }
            }

            override fun onConnected() {
                Log.d("WebRTCApp", "WebSocket connected")
            }

            override fun onDisconnected() {
                Log.d("WebRTCApp", "WebSocket disconnected")
            }

            override fun onError(error: String) {
                Log.e("WebRTCApp", "WebSocket error: $error")
            }
        })
    }

    private fun handleOffer(message: JSONObject) {
        val sdp = SessionDescription(
            SessionDescription.Type.OFFER,
            message.getJSONObject("sdp").getString("sdp")
        )

        webRTCClient.setRemoteDescription(sdp, object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                webRTCClient.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        desc?.let {
                            val answerMessage = JSONObject().apply {
                                put("type", "answer")
                                put("sdp", JSONObject().apply {
                                    put("type", it.type.canonicalForm())
                                    put("sdp", it.description)
                                })
                            }
                            webSocketClient.send(answerMessage)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        Log.e("WebRTCApp", "Failed to create answer: $error")
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e("WebRTCApp", "Failed to set answer: $error")
                    }
                })
            }
            override fun onCreateFailure(error: String?) {
                Log.e("WebRTCApp", "Failed to create offer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTCApp", "Failed to set offer: $error")
            }
        })
    }

    private fun handleAnswer(message: JSONObject) {
        val sdp = SessionDescription(
            SessionDescription.Type.ANSWER,
            message.getJSONObject("sdp").getString("sdp")
        )
        webRTCClient.setRemoteDescription(sdp, object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e("WebRTCApp", "Failed to create answer: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.e("WebRTCApp", "Failed to set answer: $error")
            }
        })
    }

    private fun handleIceCandidate(message: JSONObject) {
        val ice = message.getJSONObject("ice")
        val candidate = IceCandidate(
            ice.getString("sdpMid"),
            ice.getInt("sdpMLineIndex"),
            ice.getString("sdp")
        )
        webRTCClient.addIceCandidate(candidate)
    }

    private fun connectToRoom(username: String, room: String) {
        webSocketClient.connect("wss://anybet.site/ws")

        val joinMessage = JSONObject().apply {
            put("type", "join")
            put("username", username)
            put("room", room)
        }
        webSocketClient.send(joinMessage)
    }

    private fun startCall() {
        webRTCClient.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    val offerMessage = JSONObject().apply {
                        put("type", "offer")
                        put("sdp", JSONObject().apply {
                            put("type", it.type.canonicalForm())
                            put("sdp", it.description)
                        })
                    }
                    webSocketClient.send(offerMessage)
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
        webSocketClient.disconnect()
        webRTCClient.close()
        eglBase.release()
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