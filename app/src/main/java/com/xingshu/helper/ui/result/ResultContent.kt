package com.xingshu.helper.ui.result

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshu.helper.data.model.GeneratedResult
import com.xingshu.helper.data.model.GenerateState
import com.xingshu.helper.data.model.PanelScreen
import com.xingshu.helper.data.model.RagMatch
import com.xingshu.helper.service.WeChatAccessibilityProbe
import com.xingshu.helper.ui.panel.PanelUiState
import com.xingshu.helper.ui.panel.PanelViewModel
import com.xingshu.helper.ui.panel.ReferencedQa

@Composable
fun ResultContent(state: PanelUiState, viewModel: PanelViewModel, onClose: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            // 复制成功 → 短暂显示 ✓ 反馈 → 自动关闭面板，让用户切回微信粘贴
            delay(600)
            onClose()
            copiedLabel = null
        }
    }

    // 统一的"填入微信"动作。
    // 关键：当前面板是悬浮窗 → 用户必须先回到微信，无障碍服务才能拿到微信对话页的 root。
    // 所以正确的时序是：检查权限 → 关面板 → 启动微信（重用最近的对话页）→ 等渲染 → 填入。
    // Toast 在悬浮窗 context 上会被系统静默丢弃，反馈靠 ViewModel.snackbar；snackbar 在
    // 下一次面板打开时仍能看到（误关保留逻辑已就位）。
    val fillToWeChat: (String) -> Unit = { text ->
        Log.i("ResultContent", "fillToWeChat clicked, isReady=${WeChatAccessibilityProbe.isReady()}, text=${text.take(30)}")
        if (!WeChatAccessibilityProbe.isReady()) {
            // 未授权：不关面板，弹系统设置页让用户开权限后回来再点
            viewModel.postSnackbar("请先开启无障碍权限")
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            // 已授权：先复制兜底（万一拉起微信失败用户也能粘贴），关面板，启动微信，
            // 延时让微信渲染完输入框，再触发 SET_TEXT
            copyText(context, text)
            onClose()
            scope.launch {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launchIntent == null) {
                    viewModel.postSnackbar("未找到微信，已复制到剪贴板")
                    return@launch
                }
                context.startActivity(launchIntent)
                // 等微信前台渲染。800ms 是经验值：太短输入框还没绑，太长用户感觉卡
                delay(800)
                val result = WeChatAccessibilityProbe.fillReplyToWeChat(text)
                Log.d("ResultContent", "fillToWeChat result=$result")
                val msg = when (result) {
                    WeChatAccessibilityProbe.Companion.FillResult.Success ->
                        "已填入微信"
                    WeChatAccessibilityProbe.Companion.FillResult.InputBoxNotFound ->
                        "请打开具体对话页后长按粘贴（已复制）"
                    WeChatAccessibilityProbe.Companion.FillResult.NotInWeChat ->
                        "微信未在前台，请打开后长按粘贴（已复制）"
                    else -> "填入失败，请长按粘贴（已复制）"
                }
                viewModel.postSnackbar(msg)
            }
        }
    }

    when (val genState = state.generateState) {
        is GenerateState.Loading -> {
            Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("正在生成回复…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        is GenerateState.Error -> {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(genState.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                Button(onClick = { viewModel.navigateTo(PanelScreen.MAIN) }) {
                    Text("返回重试")
                }
            }
        }

        is GenerateState.Success -> {
            val result = genState.result

            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                if (result.isDirectMatch) {
                    // RAG 直接匹配模式：展示相似历史回答
                    item {
                        Text(
                            "相似历史回答（${result.ragMatches.size} 条）",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    result.ragMatches.forEachIndexed { index, match ->
                        item {
                            RagMatchCard(
                                rank = index + 1,
                                match = match,
                                copied = copiedLabel == "rag_$index",
                                onCopy = {
                                    copyText(context, match.answer)
                                    copiedLabel = "rag_$index"
                                },
                                onFill = { fillToWeChat(match.answer) },
                            )
                        }
                    }
                } else {
                    // RAG + AI 模式：展示 AI 生成的三版回复
                    if (result.isSensitive) {
                        item { SensitiveWarning(note = result.sensitiveNote) }
                    }

                    item {
                        ReplyCard(
                            label = "简短版",
                            labelColor = MaterialTheme.colorScheme.primary,
                            content = result.shortVersion,
                            copied = copiedLabel == "short",
                            onCopy = {
                                copyText(context, result.shortVersion)
                                copiedLabel = "short"
                            },
                            onFill = { fillToWeChat(result.shortVersion) }
                        )
                    }

                    item {
                        ReplyCard(
                            label = "自然版",
                            labelColor = MaterialTheme.colorScheme.secondary,
                            content = result.naturalVersion,
                            copied = copiedLabel == "natural",
                            onCopy = {
                                copyText(context, result.naturalVersion)
                                copiedLabel = "natural"
                            },
                            onFill = { fillToWeChat(result.naturalVersion) }
                        )
                    }

                    item {
                        ReplyCard(
                            label = "邀约版",
                            labelColor = MaterialTheme.colorScheme.tertiary,
                            content = result.inviteVersion,
                            copied = copiedLabel == "invite",
                            onCopy = {
                                copyText(context, result.inviteVersion)
                                copiedLabel = "invite"
                            },
                            onFill = { fillToWeChat(result.inviteVersion) }
                        )
                    }

                    item { MetaInfo(result = result) }
                }

                // RAG 直接匹配模式下 referencedQas 与上方卡片完全相同，不重复展示
                if (!result.isDirectMatch && state.referencedQas.isNotEmpty()) {
                    item { ReferenceSources(items = state.referencedQas) }
                }
                }

                // 固定底部按钮栏：内容滚动时按钮不消失，操作摩擦更低
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.generateWithAi() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (result.isDirectMatch) "结果不满意？结合 AI 生成"
                            else "再来一版（重新生成）",
                            fontSize = 14.sp
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.navigateTo(PanelScreen.MAIN) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("返回继续")
                    }
                }
            }
        }

        else -> {}
    }
}

@Composable
private fun SensitiveWarning(note: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp).padding(top = 2.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "敏感问题 — 建议人工确认后再发送",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
            if (note.isNotBlank()) {
                Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ReplyCard(
    label: String,
    labelColor: androidx.compose.ui.graphics.Color,
    content: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onFill: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, color = labelColor, fontSize = 13.sp)
        Text(content, fontSize = 14.sp, lineHeight = 22.sp)
        ReplyActions(copied = copied, onCopy = onCopy, onFill = onFill)
    }
}

/**
 * 复制 + 填入微信 两个等宽按钮。
 * 主操作"填入微信"放右侧（拇指更易达），失败/未授权时会自动回退到剪贴板复制。
 */
@Composable
private fun ReplyActions(copied: Boolean, onCopy: () -> Unit, onFill: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onCopy,
            enabled = !copied,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.weight(1f).height(34.dp)
        ) {
            if (copied) {
                Text("已复制 ✓", fontSize = 12.sp)
            } else {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制", fontSize = 12.sp)
            }
        }
        Button(
            onClick = onFill,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.weight(1f).height(34.dp)
        ) {
            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("填入微信", fontSize = 12.sp)
        }
    }
}

@Composable
private fun MetaInfo(result: GeneratedResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (result.intent.isNotBlank()) {
            MetaRow(label = "客户意向", value = result.intent)
        }
        if (result.nextStep.isNotBlank()) {
            MetaRow(label = "下一步", value = result.nextStep)
        }
        if (result.humanConfirm.isNotBlank()) {
            MetaRow(label = "人工确认", value = result.humanConfirm, isWarning = true)
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String, isWarning: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 56.dp)
        )
        Text(
            value,
            fontSize = 12.sp,
            color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RagMatchCard(
    rank: Int,
    match: RagMatch,
    copied: Boolean,
    onCopy: () -> Unit,
    onFill: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "#$rank",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                match.scene,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            val pct = (match.score * 100).toInt()
            Text(
                "${pct}%",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(match.answer, fontSize = 14.sp, lineHeight = 22.sp)
        ReplyActions(copied = copied, onCopy = onCopy, onFill = onFill)
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("reply", text))
}

@Composable
private fun ReferenceSources(items: List<ReferencedQa>) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "参考话术（${items.size} 条历史对话）",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "收起" else "展开", fontSize = 12.sp)
            }
        }
        if (expanded) {
            items.forEachIndexed { idx, ref ->
                val q = ref.item
                val question = q.questions.firstOrNull().orEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${idx + 1}. [${q.scene}]",
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "相似度 ${"%.2f".format(ref.score)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Q：$question",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 16.sp
                    )
                    if (q.answer.isNotBlank()) {
                        Text(
                            text = "A：${q.answer}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}
