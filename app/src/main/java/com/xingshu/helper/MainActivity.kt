package com.xingshu.helper

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshu.helper.data.model.DialogRole
import com.xingshu.helper.data.model.VisionState
import com.xingshu.helper.data.repository.VisionRepository
import com.xingshu.helper.service.CaptureCoordinator
import com.xingshu.helper.service.FloatingBallService
import com.xingshu.helper.service.ScreenCaptureService
import com.xingshu.helper.ui.theme.XingShuTheme
import com.xingshu.helper.update.UpdateBanner
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Result returns immediately on some ROMs, re-check permission on resume
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen() }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingBallService::class.java)
        startForegroundService(intent)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    @Composable
    private fun MainScreen() {
        var hasPermission by remember { mutableStateOf(Settings.canDrawOverlays(this)) }

        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(500)
                hasPermission = Settings.canDrawOverlays(this@MainActivity)
            }
        }

        XingShuTheme {
            Scaffold { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(Modifier.height(24.dp))

                    Text(
                        "行恕客服助手",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        "书画培训 AI 客服 Copilot",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))
                    UpdateBanner()
                    Spacer(Modifier.height(16.dp))

                    if (hasPermission) {
                        PermissionGranted()
                    } else {
                        PermissionRequired(onGrant = { requestOverlayPermission() })
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    VisionDebugSection()

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    AutoCaptureDebugSection()
                }
            }
        }
    }

    @Composable
    private fun AutoCaptureDebugSection() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val repo = remember { VisionRepository() }
        var visionState by remember { mutableStateOf<VisionState>(VisionState.Idle) }
        val isCapturing by CaptureCoordinator.isCapturing.collectAsState()

        // 监听截屏结果
        LaunchedEffect(Unit) {
            CaptureCoordinator.events.collect { event ->
                when (event) {
                    is CaptureCoordinator.Event.Success -> {
                        Toast.makeText(context, "截屏成功，正在识别…", Toast.LENGTH_SHORT).show()
                        repo.extractDialog(event.bitmap).collect { visionState = it }
                    }
                    is CaptureCoordinator.Event.Error -> {
                        visionState = VisionState.Error(event.message)
                    }
                }
            }
        }

        val projectionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK || result.data == null) {
                CaptureCoordinator.setCapturing(false)
                Toast.makeText(context, "未授权截屏", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            Toast.makeText(
                context,
                "已授权，3 秒后截屏当前屏幕（请保持当前界面）",
                Toast.LENGTH_LONG
            ).show()
            val svcIntent = ScreenCaptureService.newStartIntent(
                context, result.resultCode, result.data!!, delayMs = 3000L
            )
            context.startForegroundService(svcIntent)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "自动截屏 OCR POC",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "授权一次截屏权限，3 秒后自动抓取屏幕并识别对话",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            OutlinedButton(
                enabled = !isCapturing && visionState !is VisionState.Loading,
                onClick = {
                    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager
                    CaptureCoordinator.setCapturing(true)
                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                }
            ) {
                Text(
                    when {
                        isCapturing -> "等待截屏中…"
                        visionState is VisionState.Loading -> "识别中…"
                        else -> "开始自动截屏识别"
                    }
                )
            }
        }

        if (visionState !is VisionState.Idle && visionState !is VisionState.Loading) {
            VisionResultDialog(state = visionState, onDismiss = { visionState = VisionState.Idle })
        }
    }

    @Composable
    private fun VisionDebugSection() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val repo = remember { VisionRepository() }
        var state by remember { mutableStateOf<VisionState>(VisionState.Idle) }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val bitmap = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                            decoder.isMutableRequired = false
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    }
                } catch (e: Exception) {
                    state = VisionState.Error("图片读取失败：${e.message}")
                    return@launch
                }
                repo.extractDialog(bitmap).collect { state = it }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "视觉识别 POC",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "选择一张微信聊天截图，测试 qwen-vl-max 提取对话效果",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = state !is VisionState.Loading
            ) {
                Text(if (state is VisionState.Loading) "识别中…" else "选择截图测试")
            }
        }

        if (state !is VisionState.Idle && state !is VisionState.Loading) {
            VisionResultDialog(state = state, onDismiss = { state = VisionState.Idle })
        }
    }

    @Composable
    private fun VisionResultDialog(state: VisionState, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            title = { Text("视觉识别结果") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (state) {
                        is VisionState.Success -> {
                            Text(
                                "提取 ${state.messages.size} 条 · prompt=${state.promptTokens} tokens, completion=${state.completionTokens} tokens",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider()
                            if (state.messages.isEmpty()) {
                                Text("未识别到对话气泡", fontSize = 13.sp)
                            }
                            state.messages.forEach { msg ->
                                val tag = if (msg.role == DialogRole.CUSTOMER) "👤 客户" else "🟢 我"
                                Text(
                                    "$tag\n${msg.text}",
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                            HorizontalDivider()
                            Text(
                                "原始 JSON：",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                state.rawJson,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is VisionState.Error -> {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                        }
                        else -> Unit
                    }
                }
            }
        )
    }

    @Composable
    private fun PermissionGranted() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "悬浮球已启动",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "屏幕侧边的红色「行」字悬浮球已就绪。\n切换到微信后，随时点击悬浮球使用助手。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(8.dp))

            OutlinedButton(onClick = { finish() }) {
                Text("切换到微信")
            }
        }
    }

    @Composable
    private fun PermissionRequired(onGrant: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Text(
                "需要「悬浮窗」权限",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "助手需要在其他应用上方显示悬浮球。\n点击下方按钮，在系统设置中开启权限后返回即可。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("去开启悬浮窗权限")
            }

            Text(
                "开启后回到本页面，悬浮球会自动启动",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
