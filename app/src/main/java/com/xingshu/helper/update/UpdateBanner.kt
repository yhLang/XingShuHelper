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

@Composable
fun UpdateBanner() {
    if (!UpdateChecker.isConfigured()) return
    val context = LocalContext.current
    var state by remember { mutableStateOf<UpdateChecker.State>(UpdateChecker.State.Idle) }

    LaunchedEffect(Unit) {
        UpdateChecker.check().collect { state = it }
    }

    when (val s = state) {
        is UpdateChecker.State.Available -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "发现新版本 v${s.latestVersion}（当前 v${BuildConfig.VERSION_NAME}）",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (s.notes.isNotBlank()) {
                    Text(
                        s.notes.take(300),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = {
                        state = UpdateChecker.State.Downloading(0f)
                    }) { Text("下载并安装") }
                    TextButton(onClick = { state = UpdateChecker.State.Idle }) { Text("稍后") }
                }
            }
            // 触发下载
            LaunchedEffect(s) {
                UpdateChecker.download(context, s).collect { state = it }
            }
        }
        is UpdateChecker.State.Downloading -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("下载更新中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                LinearProgressIndicator(
                    progress = { s.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is UpdateChecker.State.ReadyToInstall -> {
            LaunchedEffect(s.apk) {
                UpdateChecker.launchInstall(context, s.apk)
            }
            Text(
                "已下载，正在拉起安装程序…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is UpdateChecker.State.Error -> {
            Text(
                "检查更新失败：${s.message}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        else -> Unit
    }
}
