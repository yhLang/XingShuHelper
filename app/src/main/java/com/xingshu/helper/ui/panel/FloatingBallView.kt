package com.xingshu.helper.ui.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.xingshu.helper.R
import com.xingshu.helper.ui.theme.XingShuTheme

@Composable
fun FloatingBallView() {
    XingShuTheme {
        Surface(
            modifier = Modifier
                .size(56.dp)
                .shadow(8.dp, CircleShape),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.secondary),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.ic_sun_emblem),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                )
            }
        }
    }
}
