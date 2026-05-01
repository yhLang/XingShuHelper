package com.xingshu.helper.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.xingshu.helper.BuildConfig
import java.io.File

@Composable
fun UpdateBanner() {
    if (!UpdateChecker.isConfigured()) return
    val context = LocalContext.current

    var available by remember { mutableStateOf<UpdateChecker.State.Available?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var indeterminate by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 检查触发计数：启动时 +1，之后每次 ON_RESUME（从后台切回）再 +1。
    // 只用 LaunchedEffect(Unit) 的话只会在首次进入组合时检查一次，App 在后台
    // 待几小时再切回来，永远拿不到新版本——必须重启 App 才检测到。
    var checkTrigger by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(checkTrigger) {
        // 已经在下载或下载完成的，不重复检查（避免反复弹横幅）
        if (downloading || ready != null) return@LaunchedEffect
        try {
            UpdateChecker.check().collect { s ->
                when (s) {
                    is UpdateChecker.State.Available -> {
                        available = s
                        downloading = true   // 自动开始下载
                    }
                    is UpdateChecker.State.Error -> error = s.message
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            error = "检查更新异常：${e.message}"
        }
    }

    // 下载触发：用户点"取消"会把 downloading 设回 false → 协程被取消
    LaunchedEffect(downloading) {
        if (!downloading) return@LaunchedEffect
        val a = available ?: run {
            downloading = false
            return@LaunchedEffect
        }
        try {
            UpdateChecker.download(context, a).collect { s ->
                when (s) {
                    is UpdateChecker.State.Downloading -> {
                        progress = s.progress
                        indeterminate = s.progress <= 0f
                    }
                    is UpdateChecker.State.ReadyToInstall -> {
                        ready = s.apk
                        downloading = false
                    }
                    is UpdateChecker.State.Error -> {
                        error = s.message
                        downloading = false
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            error = "下载异常：${e.message}"
            downloading = false
        }
    }

    // 下载完成自动拉起安装器
    LaunchedEffect(ready) {
        ready?.let {
            try {
                UpdateChecker.launchInstall(context, it)
            } catch (e: Exception) {
                error = "拉起安装器失败：${e.message}"
            }
        }
    }

    when {
        ready != null -> {
            Text(
                "已下载 v${available?.latestVersion ?: ""}，正在拉起安装程序…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        downloading -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val ver = available?.latestVersion ?: ""
                    Text(
                        "下载更新中 v$ver…${if (progress > 0f) " ${(progress * 100).toInt()}%" else ""}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (indeterminate) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(onClick = { downloading = false }) { Text("取消", fontSize = 11.sp) }
                }
            }
        }
        available != null -> {
            val a = available!!
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "发现新版本 v${a.latestVersion}（当前 v${BuildConfig.VERSION_NAME}）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (a.notes.isNotBlank()) {
                        Text(
                            a.notes.take(300),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = {
                            progress = 0f
                            indeterminate = true
                            downloading = true
                        }) { Text("下载并安装") }
                        TextButton(onClick = { available = null }) { Text("稍后") }
                    }
                }
            }
        }
        error != null -> {
            Text(
                "更新检查/下载失败：$error",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
