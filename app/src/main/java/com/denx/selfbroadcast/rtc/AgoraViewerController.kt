
package com.denx.selfbroadcast.rtc

import android.content.Context
import android.view.SurfaceView
import androidx.compose.runtime.mutableStateOf
import com.denx.selfbroadcast.AppConfig
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import kotlinx.coroutines.runBlocking

class AgoraViewerController(private val context: Context) {
    private var engine: RtcEngine? = null
    private var renderSurface: SurfaceView? = null
    val status = mutableStateOf("Siap menonton")
    val connectedUid = mutableStateOf<Int?>(null)

    private val handler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            status.value = "Terhubung ke room $channel"
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            connectedUid.value = uid
            status.value = "Host live terdeteksi"
            renderSurface?.let { surface ->
                engine?.setupRemoteVideo(VideoCanvas(surface, VideoCanvas.RENDER_MODE_FIT, uid))
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            connectedUid.value = null
            status.value = "Host offline"
        }
    }

    fun ensureEngine() {
        if (engine != null) return
        val config = RtcEngineConfig().apply {
            mContext = context.applicationContext
            mAppId = AppConfig.agoraAppId
            mEventHandler = handler
        }
        engine = RtcEngine.create(config).apply {
            enableVideo()
        }
    }

    suspend fun join(channelId: String, container: SurfaceView) {
        ensureEngine()
        renderSurface = container
        val token = AgoraTokenClient.fetchRtcToken(channelId, "audience", 0)
        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_AUDIENCE
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            autoSubscribeAudio = true
            autoSubscribeVideo = true
            publishMicrophoneTrack = false
            publishCameraTrack = false
        }
        engine?.joinChannel(token, channelId, 0, options)
    }

    fun leave() {
        try {
            engine?.leaveChannel()
        } catch (_: Throwable) {
        }
        try {
            RtcEngine.destroy()
        } catch (_: Throwable) {
        }
        engine = null
        renderSurface = null
        connectedUid.value = null
        status.value = "Siap menonton"
    }
}
