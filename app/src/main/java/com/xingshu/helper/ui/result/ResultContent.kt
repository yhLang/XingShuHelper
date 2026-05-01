package com.xingshu.helper.ui.result

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshu.helper.data.model.GenerateState
import com.xingshu.helper.data.model.PanelScreen
import com.xingshu.helper.ui.panel.PanelUiState
import com.xingshu.helper.ui.panel.PanelViewModel
import com.xingshu.helper.ui.panel.ReferencedQa

@Composable
fun ResultContent(state: PanelUiState, viewModel: PanelViewModel, onClose: () -> Unit = {}) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            // 复制成功 → 短暂显示 ✓ → 标记结果已消费 → 自动关闭面板，
            // 让用户切回微信粘贴。下次再开悬浮窗会回首页开始新一轮。
            delay(600)
            viewModel.markResultConsumed()
            onClose()
            copied = false
        }
    }

    when (val genState = state.generateState) {
        is GenerateState.Loading -> {
            Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("正在思考…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        is GenerateState.Streaming -> ReplyView(
            text = genState.text,
            isStreaming = true,
            copied = copied,
            referencedQas = state.referencedQas,
            onCopy = { /* 流式中不允许复制 */ },
            onRegenerate = { viewModel.regenerate() },
            onBack = { viewModel.navigateTo(PanelScreen.MAIN) },
        )

        is GenerateState.Success -> ReplyView(
            text = genState.text,
            isStreaming = false,
            copied = copied,
            referencedQas = state.referencedQas,
            onCopy = {
                copyText(context, genState.text)
                copied = true
            },
            onRegenerate = { viewModel.regenerate() },
            onBack = { viewModel.navigateTo(PanelScreen.MAIN) },
        )

        else -> {}
    }
}

@Composable
private fun ReplyView(
    text: String,
    isStreaming: Boolean,
    copied: Boolean,
    referencedQas: List<ReferencedQa>,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()

    // 流式中：每次 text 增长就把列表滚到底部，模拟"自动跟读"。
    LaunchedEffect(text, isStreaming) {
        if (isStreaming && text.isNotEmpty()) {
            listState.animateScrollToItem(0)  // 内容只有 1-2 个 item，滚到第一项确保 ReplyCard 顶部可见
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ReplyCard(
                    content = text,
                    isStreaming = isStreaming,
                    copied = copied,
                    onCopy = onCopy,
                )
            }
            if (referencedQas.isNotEmpty()) {
                item { ReferenceSources(items = referencedQas) }
            }
        }

        // 固定底部按钮栏：流式中禁用"再来一版"防误触；返回随时可点
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRegenerate,
                enabled = !isStreaming,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("再来一版（重新生成）", fontSize = 14.sp)
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("返回继续")
            }
        }
    }
}

@Composable
private fun ReplyCard(
    content: String,
    isStreaming: Boolean,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "AI 回复",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                    )
                    if (isStreaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onCopy,
                    enabled = !copied && !isStreaming,
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
            Text(
                content,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("reply", text))
}

@Composable
private fun ReferenceSources(items: List<ReferencedQa>) {
    var showAll by remember { mutableStateOf(false) }
    val displayItems = if (showAll) items else items.take(1)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "参考金标（${items.size} 条相似历史）",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (items.size > 1) {
                    TextButton(onClick = { showAll = !showAll }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (showAll) "收起" else "展开其余 ${items.size - 1}", fontSize = 12.sp)
                    }
                }
            }
            displayItems.forEachIndexed { idx, ref ->
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
                            color = MaterialTheme.colorScheme.secondary
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
