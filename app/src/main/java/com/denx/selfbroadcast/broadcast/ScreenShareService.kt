package com.denx.selfbroadcast.broadcast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.denx.selfbroadcast.AppConfig
import com.denx.selfbroadcast.MainActivity
import com.denx.selfbroadcast.R
import com.denx.selfbroadcast.rtc.AgoraTokenClient
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.NV21Buffer
import io.agora.rtc2.video.VideoFrame
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class ScreenShareService : Service() {

    companion object {
        const val ACTION_START = "com.denx.selfbroadcast.broadcast.START"
        const val ACTION_STOP = "com.denx.selfbroadcast.broadcast.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_STATUS_TEXT = "extra_status_text"

        private const val NOTIFICATION_CHANNEL_ID = "self_broadcast_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var rtcEngine: RtcEngine? = null
    private var customVideoTrackId: Int = -1
    private var lastFramePushAt = 0L
    private var roomId: String = ""
    private var projectionReady = false

    private val handler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            updateNotification("Live di room $channel")
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            // Keep silent, but this helps debugging in logs if needed later.
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBroadcast()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.parcelableIntentExtra(EXTRA_DATA)

                startForeground(NOTIFICATION_ID, buildNotification("Memulai broadcast..."), serviceType())
                if (roomId.isBlank() || resultCode != Activity.RESULT_OK || data == null) {
                    updateNotification("Gagal memulai broadcast")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startBroadcast(resultCode, data)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startBroadcast(resultCode: Int, data: Intent) {
        if (projectionReady) return
        projectionReady = true

        val metrics = resources.displayMetrics
        val width = roundEven(min(metrics.widthPixels, 720))
        val height = roundEven((metrics.heightPixels.toFloat() * width / metrics.widthPixels).toInt().coerceAtLeast(480))
        val density = metrics.densityDpi

        captureThread = HandlerThread("screen_capture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    pushImageFrame(image, width, height)
                } catch (_: Throwable) {
                } finally {
                    image.close()
                }
            }, captureHandler)
        }

        rtcEngine = createEngine()
        customVideoTrackId = RtcEngine.createCustomVideoTrack()
        joinAgora(roomId, customVideoTrackId)

        virtualDisplay = projection?.createVirtualDisplay(
            "self-broadcast",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        )

        updateNotification("Broadcast aktif di room $roomId")
    }

    private fun createEngine(): RtcEngine {
        val config = RtcEngineConfig().apply {
            mContext = applicationContext
            mAppId = AppConfig.agoraAppId
            mEventHandler = handler
        }
        return RtcEngine.create(config).apply {
            enableVideo()
        }
    }

    private fun joinAgora(channelId: String, trackId: Int) {
        val engine = rtcEngine ?: return
        Thread {
            val token = try {
                kotlinx.coroutines.runBlocking {
                    AgoraTokenClient.fetchRtcToken(channelId, "publisher", 0)
                }
            } catch (_: Throwable) {
                null
            }

            val options = ChannelMediaOptions().apply {
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                autoSubscribeAudio = false
                autoSubscribeVideo = false
                publishMicrophoneTrack = false
                publishCameraTrack = false
                publishCustomVideoTrack = true
                customVideoTrackId = trackId
            }
            engine.joinChannel(token, channelId, 0, options)
        }.start()
    }

    private fun pushImageFrame(image: android.media.Image, width: Int, height: Int) {
        val now = System.currentTimeMillis()
        if (now - lastFramePushAt < 100) return
        lastFramePushAt = now

        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rgba = ByteArray(buffer.remaining())
        buffer.get(rgba)

        val nv21 = rgbaToNv21(rgba, width, height, rowStride, pixelStride)
        val frameBuffer = NV21Buffer(nv21, width, height, null)

        val engine = rtcEngine ?: return
        val ts = engine.getCurrentMonotonicTimeInMs() * 1_000_000
        val frame = VideoFrame(frameBuffer, 0, ts)
        engine.pushExternalVideoFrameById(frame, customVideoTrackId)
    }

    private fun rgbaToNv21(src: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        val frameSize = width * height
        val out = ByteArray(frameSize * 3 / 2)
        var yIndex = 0
        var uvIndex = frameSize
        val yuv = IntArray(3)

        for (j in 0 until height) {
            val rowStart = j * rowStride
            for (i in 0 until width) {
                val srcIndex = rowStart + i * pixelStride
                val r = src[srcIndex].toInt() and 0xFF
                val g = src[srcIndex + 1].toInt() and 0xFF
                val b = src[srcIndex + 2].toInt() and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    private fun stopBroadcast() {
        try { rtcEngine?.leaveChannel() } catch (_: Throwable) {}
        try { rtcEngine?.destroyCustomVideoTrack(customVideoTrackId) } catch (_: Throwable) {}
        try { RtcEngine.destroy() } catch (_: Throwable) {}

        rtcEngine = null
        customVideoTrackId = -1
        projectionReady = false

        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}

        virtualDisplay = null
        imageReader = null
        projection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        updateNotification("Broadcast dihentikan")
    }

    override fun onDestroy() {
        stopBroadcast()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Self Broadcast",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, ScreenShareService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launchIntent = Intent(this, MainActivity::class.java)
        val contentPending = PendingIntent.getActivity(
            this, 1, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Self Broadcast aktif")
            .setContentText(text)
            .setContentIntent(contentPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun serviceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
    }

    private fun Intent.parcelableIntentExtra(key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(key)
    }

    private fun roundEven(value: Int): Int = max(2, if (value % 2 == 0) value else value - 1)
}
