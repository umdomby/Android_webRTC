package com.example.webrtcapp
import android.util.Log
import android.content.Context
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val observer: PeerConnection.Observer
) {
    private val peerConnectionFactory: PeerConnectionFactory
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )
    private val peerConnection: PeerConnection
    private val eglBase: EglBase = EglBase.create()

    private lateinit var localVideoTrack: VideoTrack
    private lateinit var localAudioTrack: AudioTrack

    init {
        Log.d("WebRTCApp", "Initializing WebRTCClient")

        // Проверка поддержки OpenGL ES 2.0
        if (eglBase.eglBaseContext == null) {
            Log.e("WebRTCApp", "OpenGL ES 2.0 not supported")
            throw RuntimeException("OpenGL ES 2.0 not supported")
        }

        // Инициализация WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)!!
    }

    fun createLocalStream(localVideoOutput: SurfaceViewRenderer) {
        Log.d("WebRTCApp", "Creating local stream")

        // Создаем аудио трек
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource)
        peerConnection.addTrack(localAudioTrack)

        // Создаем видео трек
        val surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread",
            eglBase.eglBaseContext
        )
        val videoSource = peerConnectionFactory.createVideoSource(false)
        val videoCapturer = createCameraCapturer()
        videoCapturer.initialize(
            surfaceTextureHelper,
            context,
            videoSource.capturerObserver
        )
        videoCapturer.startCapture(640, 480, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource)
        localVideoTrack.addSink(localVideoOutput)
        peerConnection.addTrack(localVideoTrack)

        Log.d("WebRTCApp", "Local stream created and added to PeerConnection")
    }

    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }

        throw RuntimeException("Front facing camera not found")
    }

    fun createOffer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection.createOffer(sdpObserver, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription, sdpObserver: SdpObserver) {
        peerConnection.setRemoteDescription(sdpObserver, sdp)
    }

    fun addIceCandidate(iceCandidate: IceCandidate) {
        peerConnection.addIceCandidate(iceCandidate)
    }

    fun close() {
        peerConnection.dispose()
        peerConnectionFactory.dispose()
    }
}