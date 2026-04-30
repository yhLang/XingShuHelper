package com.xingshu.helper.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshu.helper.data.account.BusinessAccount
import com.xingshu.helper.data.repository.CorpusSyncManager

@Composable
fun SettingsContent(
    currentAccount: BusinessAccount,
    corpusReady: Boolean,
    corpusVersion: Int,
    corpusSync: CorpusSyncManager.State,
    corpusSyncConfigured: Boolean,
    onSwitchAccount: (BusinessAccount) -> Unit,
    onSyncCorpus: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        SectionTitle("业务账号")
        Text(
            "选择当前手机绑定的微信号。每个账号有独立的话术库，切换后会重新加载。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BusinessAccount.values().forEach { account ->
                AccountRow(
                    account = account,
                    selected = account == currentAccount,
                    onClick = { onSwitchAccount(account) }
                )
            }
        }
        Text(
            text = if (corpusReady) "话术库已加载" else "话术库加载中…",
            fontSize = 12.sp,
            color = if (corpusReady) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (corpusSyncConfigured) {
            HorizontalDivider()
            SectionTitle("话术库同步")
            CorpusSyncRow(
                version = corpusVersion,
                state = corpusSync,
                onCheck = onSyncCorpus,
            )
        }

        HorizontalDivider()

        SectionTitle("【调试】无障碍探测（PoC）")
        AccessibilityProbeRow()

        HorizontalDivider()

        SectionTitle("关于")
        Text(
            "行恕书画艺术培训中心专属 AI 客服助手\n版本 1.0.0\n\n回复由 AI 辅助生成，请确认内容准确后再发送。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )

        HorizontalDivider()

        SectionTitle("隐私说明")
        Text(
            "• 本 App 不读取微信数据库\n• 不自动上传剪贴板内容\n• 仅在您点击操作后处理文本\n• 生成回复时，消息内容会发送至阿里云百炼模型服务\n• 建议不要输入客户姓名、电话等隐私信息",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AccountRow(
    account: BusinessAccount,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = account.displayName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = account.brandName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
}

@Composable
private fun AccessibilityProbeRow() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "用于验证微信单聊节点稳定性。开启后到微信单聊里聊几条，" +
                "再用 adb logcat -s WeChatProbe 收集日志。验证完关掉即可。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ) {
            Text("打开系统无障碍设置")
        }
    }
}

@Composable
private fun CorpusSyncRow(
    version: Int,
    state: CorpusSyncManager.State,
    onCheck: () -> Unit,
) {
    val (statusText, statusColor) = when (state) {
        CorpusSyncManager.State.Idle ->
            (if (version > 0) "本地版本 v$version" else "尚未同步，使用 APK 内置语料") to
                MaterialTheme.colorScheme.onSurfaceVariant
        CorpusSyncManager.State.Checking -> "正在检查更新…" to MaterialTheme.colorScheme.primary
        is CorpusSyncManager.State.UpToDate -> "已是最新（v${state.version}）" to MaterialTheme.colorScheme.primary
        is CorpusSyncManager.State.Downloading ->
            "下载中 ${(state.progress * 100).toInt()}%" to MaterialTheme.colorScheme.primary
        is CorpusSyncManager.State.Updated ->
            "已更新到 v${state.version}（${state.count} 条）" to MaterialTheme.colorScheme.primary
        is CorpusSyncManager.State.Error -> "失败：${state.message}" to MaterialTheme.colorScheme.error
    }
    val busy = state is CorpusSyncManager.State.Checking || state is CorpusSyncManager.State.Downloading
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(statusText, fontSize = 12.sp, color = statusColor)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = onCheck, enabled = !busy) {
                Text(if (busy) "请稍候" else "检查金标更新")
            }
        }
    }
}
