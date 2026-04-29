package com.xingshu.helper.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshu.helper.data.model.BasketMessage
import com.xingshu.helper.data.model.GenerateState
import com.xingshu.helper.data.model.PanelScreen
import com.xingshu.helper.data.model.VisionState
import com.xingshu.helper.service.CaptureCoordinator
import com.xingshu.helper.ui.result.ResultContent
import com.xingshu.helper.ui.settings.SettingsContent
import com.xingshu.helper.ui.theme.XingShuTheme

@Composable
fun FloatingPanelRoot(viewModel: PanelViewModel, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.readClipboard() }

    XingShuTheme {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                PanelTopBar(
                    title = when (state.currentScreen) {
                        PanelScreen.MAIN -> "行恕客服助手"
                        PanelScreen.RESULT -> "生成结果"
                        PanelScreen.SETTINGS -> "设置"
                    },
                    currentScreen = state.currentScreen,
                    onClose = onClose,
                    onSettings = { viewModel.navigateTo(PanelScreen.SETTINGS) },
                    onBack = { viewModel.navigateTo(PanelScreen.MAIN) }
                )

                when (state.currentScreen) {
                    PanelScreen.MAIN -> MainContent(state = state, viewModel = viewModel)
                    PanelScreen.RESULT -> ResultContent(state = state, viewModel = viewModel)
                    PanelScreen.SETTINGS -> SettingsContent()
                }

                state.snackbar?.let { msg ->
                    LaunchedEffect(msg) {
                        delay(2000)
                        viewModel.clearSnackbar()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF323232))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(msg, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTopBar(
    title: String,
    currentScreen: PanelScreen,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentScreen != PanelScreen.MAIN) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹ 返回", fontSize = 15.sp)
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(start = if (currentScreen != PanelScreen.MAIN) 4.dp else 0.dp),
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp
        )
        if (currentScreen == PanelScreen.MAIN) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置", modifier = Modifier.size(20.dp))
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
        }
    }
    HorizontalDivider()
}

@Composable
private fun MainContent(state: PanelUiState, viewModel: PanelViewModel) {
    val isLoading = state.generateState is GenerateState.Loading

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Auto-capture entry
        item {
            CaptureSection(state = state, viewModel = viewModel, isLoading = isLoading)
        }

        // Clipboard preview
        item {
            ClipboardSection(state = state, viewModel = viewModel, isLoading = isLoading)
        }

        // Action buttons
        item {
            ActionButtons(state = state, viewModel = viewModel, isLoading = isLoading)
        }

        // Basket
        if (state.basket.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "本轮消息（${state.basket.size} 条）",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { viewModel.clearBasket() }, contentPadding = PaddingValues(0.dp)) {
                        Text("清空", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            items(state.basket, key = { it.id }) { msg ->
                BasketItem(message = msg, onDelete = { viewModel.removeFromBasket(msg.id) })
            }
        }
    }
}

@Composable
private fun CaptureSection(state: PanelUiState, viewModel: PanelViewModel, isLoading: Boolean) {
    val isCapturing by CaptureCoordinator.isCapturing.collectAsState()
    val isVisionLoading = state.visionState is VisionState.Loading
    val busy = isCapturing || isVisionLoading || isLoading

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "📸 截屏识别对话",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            "授权后 3 秒倒计时，期间切到要识别的微信对话窗口",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Button(
            onClick = { viewModel.startScreenCapture() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    isCapturing && !isVisionLoading -> "等待截屏…（请切到微信）"
                    isVisionLoading -> "正在识别对话…"
                    else -> "开始截屏识别"
                }
            )
        }
        if (state.dialogMessages.isNotEmpty()) {
            Text(
                "上次识别：${state.dialogMessages.size} 条对话（客户 ${state.dialogMessages.count { it.role == com.xingshu.helper.data.model.DialogRole.CUSTOMER }} / 我 ${state.dialogMessages.count { it.role == com.xingshu.helper.data.model.DialogRole.ME }}）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun ClipboardSection(state: PanelUiState, viewModel: PanelViewModel, isLoading: Boolean) {
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
            Text("剪贴板内容", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = { viewModel.readClipboard() },
                enabled = !isLoading,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("刷新", fontSize = 12.sp)
            }
        }

        when (state.clipboardStatus) {
            ClipboardStatus.EMPTY -> Text(
                "未检测到内容，请先在微信复制客户消息",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ClipboardStatus.DUPLICATE -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.clipboardPreview, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text("⚠ 该内容已在本轮中", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
            ClipboardStatus.TOO_LONG -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.clipboardPreview, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text("内容较长（${state.clipboardPreview.length} 字），建议只保留关键问题", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
            ClipboardStatus.OK -> Text(state.clipboardPreview, fontSize = 14.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActionButtons(state: PanelUiState, viewModel: PanelViewModel, isLoading: Boolean) {
    val hasClipboard = state.clipboardStatus != ClipboardStatus.EMPTY
    val hasBasket = state.basket.isNotEmpty()

    if (isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("正在生成回复…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.addToBasket() },
                enabled = hasClipboard,
                modifier = Modifier.weight(1f)
            ) { Text("加入本轮") }

            Button(
                onClick = { viewModel.generateSingle() },
                enabled = hasClipboard,
                modifier = Modifier.weight(1f)
            ) { Text("单条生成") }
        }

        Button(
            onClick = { viewModel.generateFromBasket() },
            enabled = hasBasket,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) { Text("生成综合回复（${state.basket.size} 条）") }
    }
}

@Composable
private fun BasketItem(message: BasketMessage, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message.content,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
