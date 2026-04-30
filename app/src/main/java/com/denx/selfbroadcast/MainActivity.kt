package com.denx.selfbroadcast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.denx.selfbroadcast.broadcast.ScreenShareService
import com.denx.selfbroadcast.data.UserRepository
import com.denx.selfbroadcast.rtc.AgoraViewerController
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.util.UUID

private enum class AppTab { Login, Host, Viewer }

class MainActivity : ComponentActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val userRepo by lazy { UserRepository() }
    private val prefs: SharedPreferences by lazy { getSharedPreferences("self_broadcast_prefs", MODE_PRIVATE) }
    private val viewerController by lazy { AgoraViewerController(this) }

    private var activeTab by mutableStateOf(AppTab.Login)
    private var email by mutableStateOf("")
    private var infoText by mutableStateOf("Silakan login")
    private var roomId by mutableStateOf(generateRoomId())
    private var viewerRoomId by mutableStateOf("")
    private var isBroadcasting by mutableStateOf(false)
    private var viewerConnected by mutableStateOf(false)
    private var viewerStatus by mutableStateOf("Belum join")
    private var hasAuthLink by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            infoText = "Beberapa permission belum diberikan."
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startBroadcastService(result.resultCode, result.data!!)
        } else {
            infoText = "Screen capture dibatalkan."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        auth.currentUser?.let {
            activeTab = AppTab.Host
            infoText = "Login aktif: ${it.email.orEmpty()}"
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppScreen(
                    tab = activeTab,
                    email = email,
                    infoText = infoText,
                    roomId = roomId,
                    viewerRoomId = viewerRoomId,
                    viewerStatus = viewerStatus,
                    viewerConnected = viewerConnected,
                    isBroadcasting = isBroadcasting,
                    onEmailChange = { email = it },
                    onViewerRoomChange = { viewerRoomId = it },
                    onLoginSend = { sendLoginLink() },
                    onLoginComplete = { completeLoginIfNeeded() },
                    onGenerateRoom = { roomId = generateRoomId() },
                    onStartBroadcast = { requestScreenSharePermission() },
                    onStopBroadcast = { stopBroadcastService() },
                    onOpenHost = { activeTab = AppTab.Host },
                    onOpenViewer = { activeTab = AppTab.Viewer },
                    onJoinViewer = { surfaceView -> joinViewerRoom(surfaceView) },
                    onLeaveViewer = { leaveViewerRoom() }
                )
            }
        }

        maybeAutoLoginFromLink()
        requestCommonPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        maybeAutoLoginFromLink()
    }

    private fun requestCommonPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }

        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun sendLoginLink() {
        val targetEmail = email.trim()
        if (targetEmail.isBlank()) {
            infoText = "Masukkan email dulu."
            return
        }

        prefs.edit().putString("pending_email", targetEmail).apply()

        val settings = ActionCodeSettings.newBuilder()
            .setUrl(AppConfig.emailLinkUrl)
            .setHandleCodeInApp(true)
            .setAndroidPackageName(packageName, true, null)
            .build()

        auth.sendSignInLinkToEmail(targetEmail, settings)
            .addOnSuccessListener {
                infoText = "Link login dikirim ke $targetEmail"
            }
            .addOnFailureListener { e ->
                infoText = "Gagal kirim link: ${e.message.orEmpty()}"
            }
    }

    private fun maybeAutoLoginFromLink() {
        val data = intent?.data?.toString().orEmpty()
        if (!auth.isSignInWithEmailLink(data)) return

        hasAuthLink = true
        val savedEmail = prefs.getString("pending_email", null).orEmpty()
        if (savedEmail.isBlank()) {
            infoText = "Link valid, tapi email belum tersimpan. Login ulang dari halaman awal."
            return
        }
        completeSignIn(savedEmail, data)
    }

    private fun completeLoginIfNeeded() {
        val savedEmail = prefs.getString("pending_email", null).orEmpty()
        val link = intent?.data?.toString().orEmpty()
        if (savedEmail.isNotBlank() && auth.isSignInWithEmailLink(link)) {
            completeSignIn(savedEmail, link)
        } else {
            infoText = "Buka email link di device ini, lalu kembali ke app."
        }
    }

    private fun completeSignIn(savedEmail: String, link: String) {
        auth.signInWithEmailLink(savedEmail, link)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    activeTab = AppTab.Host
                    infoText = "Login berhasil: ${user.email.orEmpty()}"
                    lifecycleScope.launch {
                        userRepo.saveCurrentUser {
                            infoText = if (it.isSuccess) {
                                "Login berhasil dan data user tersimpan."
                            } else {
                                "Login berhasil, tapi simpan Firestore gagal: ${it.exceptionOrNull()?.message.orEmpty()}"
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                infoText = "Gagal sign in: ${e.message.orEmpty()}"
            }
    }

    private fun requestScreenSharePermission() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startBroadcastService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_START
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenShareService.EXTRA_DATA, data)
            putExtra(ScreenShareService.EXTRA_ROOM_ID, roomId)
        }
        ContextCompat.startForegroundService(this, intent)
        isBroadcasting = true
        infoText = "Broadcast mulai di room $roomId"
    }

    private fun stopBroadcastService() {
        val intent = Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_STOP
        }
        startService(intent)
        isBroadcasting = false
        infoText = "Broadcast dihentikan"
    }

    private fun joinViewerRoom(container: SurfaceView) {
        val channel = viewerRoomId.trim()
        if (channel.isBlank()) {
            viewerStatus = "Masukkan Room ID dulu."
            return
        }

        viewerStatus = "Menghubungkan..."
        viewerConnected = true

        lifecycleScope.launch {
            try {
                viewerController.join(channel, container)
                viewerStatus = "Join room: $channel"
            } catch (e: Exception) {
                viewerStatus = "Gagal join: ${e.message.orEmpty()}"
                viewerConnected = false
            }
        }
    }

    private fun leaveViewerRoom() {
        viewerController.leave()
        viewerStatus = "Viewer keluar"
        viewerConnected = false
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.data != null) {
            infoText = "Link sign-in terdeteksi"
        }
    }

    private fun generateRoomId(): String = "SB-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
}

@Composable
private fun AppScreen(
    tab: AppTab,
    email: String,
    infoText: String,
    roomId: String,
    viewerRoomId: String,
    viewerStatus: String,
    viewerConnected: Boolean,
    isBroadcasting: Boolean,
    onEmailChange: (String) -> Unit,
    onViewerRoomChange: (String) -> Unit,
    onLoginSend: () -> Unit,
    onLoginComplete: () -> Unit,
    onGenerateRoom: () -> Unit,
    onStartBroadcast: () -> Unit,
    onStopBroadcast: () -> Unit,
    onOpenHost: () -> Unit,
    onOpenViewer: () -> Unit,
    onJoinViewer: (SurfaceView) -> Unit,
    onLeaveViewer: () -> Unit,
) {
    val scroll = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0F172A),
                        Color(0xFF111827),
                        Color(0xFF1F2937)
                    )
                )
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Self Broadcast",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White
            )
            Text(
                text = infoText,
                color = Color(0xFFD1D5DB)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = tab == AppTab.Host,
                    onClick = onOpenHost,
                    label = { Text("Home / Host") }
                )
                FilterChip(
                    selected = tab == AppTab.Viewer,
                    onClick = onOpenViewer,
                    label = { Text("Viewer") }
                )
            }

            when (tab) {
                AppTab.Login -> LoginCard(
                    email = email,
                    onEmailChange = onEmailChange,
                    onSendLink = onLoginSend,
                    onCompleteLogin = onLoginComplete
                )

                AppTab.Host -> HostCard(
                    roomId = roomId,
                    isBroadcasting = isBroadcasting,
                    onGenerateRoom = onGenerateRoom,
                    onStartBroadcast = onStartBroadcast,
                    onStopBroadcast = onStopBroadcast
                )

                AppTab.Viewer -> ViewerCard(
                    viewerRoomId = viewerRoomId,
                    viewerStatus = viewerStatus,
                    viewerConnected = viewerConnected,
                    onViewerRoomChange = onViewerRoomChange,
                    onJoinViewer = onJoinViewer,
                    onLeaveViewer = onLeaveViewer
                )
            }
        }
    }
}

@Composable
private fun LoginCard(
    email: String,
    onEmailChange: (String) -> Unit,
    onSendLink: () -> Unit,
    onCompleteLogin: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Login Firebase", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSendLink, modifier = Modifier.fillMaxWidth()) {
                Text("Kirim Login Link")
            }
            OutlinedButton(onClick = onCompleteLogin, modifier = Modifier.fillMaxWidth()) {
                Text("Saya sudah klik link")
            }
            Text(
                text = "Setelah link dibuka, app akan masuk otomatis. Kalau belum, tekan tombol di atas.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HostCard(
    roomId: String,
    isBroadcasting: Boolean,
    onGenerateRoom: () -> Unit,
    onStartBroadcast: () -> Unit,
    onStopBroadcast: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Home / Broadcast", style = MaterialTheme.typography.titleLarge)
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF374151), RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Room ID", color = Color(0xFF9CA3AF))
                    Text(roomId, style = MaterialTheme.typography.headlineSmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onGenerateRoom, modifier = Modifier.weight(1f)) {
                    Text("Generate Room")
                }
                Button(
                    onClick = onStartBroadcast,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Broadcast")
                }
            }
            OutlinedButton(
                onClick = onStopBroadcast,
                enabled = isBroadcasting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
            Text(
                text = "Bagikan Room ID ini ke viewer. Viewer hanya bisa menonton.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ViewerCard(
    viewerRoomId: String,
    viewerStatus: String,
    viewerConnected: Boolean,
    onViewerRoomChange: (String) -> Unit,
    onJoinViewer: (SurfaceView) -> Unit,
    onLeaveViewer: () -> Unit,
) {
    val ctx = LocalContext.current
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Viewer Mode", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = viewerRoomId,
                onValueChange = onViewerRoomChange,
                label = { Text("Room ID") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val view = SurfaceView(ctx)
                    surfaceView = view
                    onJoinViewer()
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Join")
                }
                OutlinedButton(
                    onClick = onLeaveViewer,
                    enabled = viewerConnected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Leave")
                }
            }
            Text(viewerStatus)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black, RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0xFF374151), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { surfaceView ?: SurfaceView(it).also { v -> surfaceView = v } },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = "Viewer mode hanya menampilkan live video. Tidak ada kontrol.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
