package com.xingshu.helper.ui.snippets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshu.helper.data.model.Snippet
import kotlinx.coroutines.delay

@Composable
fun SnippetsContent(snippets: List<Snippet>) {
    val context = LocalContext.current
    var copiedTitle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedTitle) {
        if (copiedTitle != null) {
            delay(1500)
            copiedTitle = null
        }
    }

    if (snippets.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "当前账号暂无常用片段。可在 assets/snippets_<account>.json 中添加。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val grouped = snippets.groupBy { it.category }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        grouped.forEach { (category, items) ->
            item(key = "header_$category") {
                Text(
                    text = "$category（${items.size}）",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items.forEach { snippet ->
                item(key = "${category}_${snippet.title}") {
                    SnippetCard(
                        snippet = snippet,
                        copied = copiedTitle == snippet.title,
                        onCopy = {
                            copyToClipboard(context, snippet.text)
                            copiedTitle = snippet.title
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SnippetCard(snippet: Snippet, copied: Boolean, onCopy: () -> Unit) {
    Surface(
        onClick = onCopy,
        shape = RoundedCornerShape(10.dp),
        color = if (copied) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    snippet.title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (copied) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (copied) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        if (copied) "已复制" else "复制",
                        fontSize = 11.sp,
                        color = if (copied) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Text(
                snippet.text,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("snippet", text))
}
