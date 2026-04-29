package com.xingshu.helper.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // 启动时检查更新——只跑一次，不依赖任何外部 state，不会被取消
    LaunchedEffect(Unit) {
        try {
            UpdateChecker.check().collect { s ->
                when (s) {
                    is UpdateChecker.State.Available -> available = s
                    is UpdateChecker.State.Error -> error = s.message
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            error = "检查更新异常：${e.message}"
        }
    }

    // 下载触发：用户点"下载并安装"才开始；用户点"取消"会把 downloading 设回 false → 协程被取消
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(12.dp),
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
        available != null -> {
            val a = available!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(12.dp),
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
        error != null -> {
            Text(
                "更新检查/下载失败：$error",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
