package com.xingshu.helper.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
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
private fun SectionTitle(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
}
