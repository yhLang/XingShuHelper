package com.xingshu.helper.ui.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.xingshu.helper.R
import com.xingshu.helper.ui.theme.XingShuTheme

// 必须用纯 Box 渲染：宿主 ComposeView 在 FloatingBallService 里挂了
// setOnTouchListener 实现拖动+点击。任何带内部 pointer/clickable 包装的 Compose
// 容器（比如 Material3 Surface）都会消费 ACTION_DOWN，导致拖动失效、点击打不开。
@Composable
fun FloatingBallView() {
    XingShuTheme {
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_sun_emblem),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
