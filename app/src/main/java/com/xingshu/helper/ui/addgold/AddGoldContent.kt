package com.xingshu.helper.ui.addgold

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingshu.helper.data.repository.GoldUploader
import com.xingshu.helper.ui.panel.AddGoldState
import com.xingshu.helper.ui.panel.PanelViewModel

@Composable
fun AddGoldContent(state: AddGoldState, viewModel: PanelViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "添加金标话术",
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Text(
            "填写场景和标准回复 → AI 反推 5-7 个客户可能问法 → 编辑确认后入库（仅当前账号生效，立即可用）",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )

        OutlinedTextField(
            value = state.scene,
            onValueChange = viewModel::updateGoldScene,
            label = { Text("场景（如：试听课、价格咨询）", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.answer,
            onValueChange = viewModel::updateGoldAnswer,
            label = { Text("标准回复 *", fontSize = 12.sp) },
            placeholder = { Text("客服推荐说的话…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.riskNote,
            onValueChange = viewModel::updateGoldRiskNote,
            label = { Text("风险提示（可选，如：不要擅自给折扣）", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.generateGoldQuestionVariants() },
                enabled = !state.generating && !state.saving && state.answer.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                if (state.generating) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("AI 生成中…", fontSize = 13.sp)
                } else {
                    Text("AI 生成 Q 变体", fontSize = 13.sp)
                }
            }
            TextButton(onClick = { viewModel.resetGoldForm() }, enabled = !state.saving) {
                Text("清空", fontSize = 13.sp)
            }
        }

        if (state.questionDrafts.isNotEmpty()) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Q 变体（${state.questionDrafts.size} 条）",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { viewModel.addGoldDraftBlank() },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("加一条", fontSize = 12.sp)
                }
            }

            state.questionDrafts.forEachIndexed { index, q ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = q,
                        onValueChange = { viewModel.updateGoldDraft(index, it) },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        singleLine = false,
                        maxLines = 2
                    )
                    IconButton(onClick = { viewModel.removeGoldDraft(index) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        state.errorMessage?.let { msg ->
            Text(
                msg,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (GoldUploader.isConfigured()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.uploadToCloud,
                    onCheckedChange = { viewModel.setUploadToCloud(it) },
                    enabled = !state.saving,
                )
                Text(
                    "同时上传到云端（让其他设备也能用）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = { viewModel.saveGoldToLocal() },
            enabled = !state.saving && !state.generating
                && state.answer.isNotBlank()
                && state.questionDrafts.any { it.isNotBlank() },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.saving) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("保存中…", fontSize = 13.sp)
            } else {
                Text("加入金标库", fontSize = 13.sp)
            }
        }
    }
}
