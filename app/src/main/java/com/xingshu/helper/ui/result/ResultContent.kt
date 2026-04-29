package com.xingshu.helper.ui.result

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
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
import com.xingshu.helper.data.model.QAItem
import com.xingshu.helper.data.model.RagMatch
import com.xingshu.helper.ui.panel.PanelUiState
import com.xingshu.helper.ui.panel.PanelViewModel
import com.xingshu.helper.ui.panel.ReferencedQa

@Composable
fun ResultContent(state: PanelUiState, viewModel: PanelViewModel) {
    val context = LocalContext.current
    var copiedLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(1500)
            copiedLabel = null
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

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
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
                            // referencedQas 顺序与 ragMatches 一致：第 i 条 QAItem 即是 ragMatches[i] 的来源
                            val sourceItem = state.referencedQas.getOrNull(index)?.item
                            RagMatchCard(
                                rank = index + 1,
                                match = match,
                                sourceItem = sourceItem,
                                copied = copiedLabel == "rag_$index",
                                onCopy = {
                                    copyText(context, match.answer)
                                    copiedLabel = "rag_$index"
                                },
                                onSave = { newAnswer, newRiskNote ->
                                    if (sourceItem != null) {
                                        viewModel.updateRagAnswer(sourceItem, newAnswer, newRiskNote)
                                    }
                                },
                                onToggleGold = {
                                    if (sourceItem != null) {
                                        if (match.isGold) viewModel.demoteFromGold(sourceItem)
                                        else viewModel.promoteToGold(sourceItem)
                                    }
                                },
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
                            }
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
                            }
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
                            }
                        )
                    }

                    item { MetaInfo(result = result) }
                }

                if (state.referencedQas.isNotEmpty()) {
                    item { ReferenceSources(items = state.referencedQas) }
                }

                // RAG-only 结果不满意时，可以基于这批检索结果再调 LLM 生成三版回复
                if (result.isDirectMatch) {
                    item {
                        Button(
                            onClick = { viewModel.generateWithAi() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("结果不满意？结合 AI 生成", fontSize = 14.sp)
                        }
                    }
                }

                item {
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
    onCopy: () -> Unit
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, color = labelColor, fontSize = 13.sp)
            FilledTonalButton(
                onClick = onCopy,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                if (copied) {
                    Text("已复制 ✓", fontSize = 12.sp)
                } else {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制", fontSize = 12.sp)
                }
            }
        }
        Text(content, fontSize = 14.sp, lineHeight = 22.sp)
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
    sourceItem: QAItem?,
    copied: Boolean,
    onCopy: () -> Unit,
    onSave: (newAnswer: String, newRiskNote: String) -> Unit,
    onToggleGold: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
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
                    color = MaterialTheme.colorScheme.onSurface
                )
                val pct = (match.score * 100).toInt()
                Text(
                    "${pct}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (match.isGold) {
                    Text(
                        "★金标",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (sourceItem?.isLocal == true) {
                    Text(
                        "本地",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sourceItem != null) {
                    IconButton(onClick = onToggleGold, modifier = Modifier.size(28.dp)) {
                        Text(
                            text = if (match.isGold) "★" else "☆",
                            fontSize = 18.sp,
                            color = if (match.isGold) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp))
                    }
                }
                FilledTonalButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    if (copied) {
                        Text("已复制 ✓", fontSize = 12.sp)
                    } else {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制", fontSize = 12.sp)
                    }
                }
            }
        }
        Text(match.answer, fontSize = 14.sp, lineHeight = 22.sp)

        if (editing && sourceItem != null) {
            InlineEditAnswer(
                scene = sourceItem.scene,
                question = sourceItem.questions.firstOrNull().orEmpty(),
                initialAnswer = sourceItem.answer,
                initialRiskNote = sourceItem.riskNote,
                onCancel = { editing = false },
                onSave = { newAnswer, newRiskNote ->
                    onSave(newAnswer, newRiskNote)
                    editing = false
                }
            )
        }
    }
}

/**
 * 内联展开的编辑区域。注意：悬浮窗在 TYPE_APPLICATION_OVERLAY 下，
 * Compose AlertDialog 会创建新的 system Window，从 Service Context 启动会因
 * BadTokenException 闪退，所以这里不能用 Dialog，改用内联展开。
 */
@Composable
private fun InlineEditAnswer(
    scene: String,
    question: String,
    initialAnswer: String,
    initialRiskNote: String,
    onCancel: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var answer by remember(initialAnswer) { mutableStateOf(initialAnswer) }
    var riskNote by remember(initialRiskNote) { mutableStateOf(initialRiskNote) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "修订：[$scene] $question",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { answer = (answer + readClipboardText(context)) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴到回复", modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("粘贴回复", fontSize = 11.sp)
            }
            TextButton(
                onClick = { riskNote = (riskNote + readClipboardText(context)) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴到风险", modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("粘贴风险", fontSize = 11.sp)
            }
        }
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            label = { Text("回复内容 *", fontSize = 12.sp) },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = riskNote,
            onValueChange = { riskNote = it },
            label = { Text("风险提示（可选）", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "保存后以本地金标入库，下次同问句优先返回修订版。",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("取消", fontSize = 12.sp) }
            Button(
                onClick = { onSave(answer, riskNote) },
                enabled = answer.isNotBlank(),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("保存", fontSize = 12.sp) }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("reply", text))
}

private fun readClipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
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
                        if (q.isGold) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "★金标",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
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
