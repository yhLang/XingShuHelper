package com.xingshu.helper.service

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * 透明 Activity，唯一职责：申请 MediaProjection 授权 → 启动 ScreenCaptureService → finish。
 *
 * Why: MediaProjection 授权弹窗只能从 Activity 触发；悬浮窗服务无法直接拉起。
 * 仅在"首次"或"projection 已被系统/用户撤销"的场景下才需要走这个 Activity；
 * 后续截屏直接复用 ScreenCaptureService 持有的 projection 实例，零交互。
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = ScreenCaptureService.newStartIntent(
                this, result.resultCode, result.data!!,
                delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 500L)
            )
            startForegroundService(svc)
        } else {
            CaptureCoordinator.setCapturing(false)
            Toast.makeText(this, "未授权截屏", Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CaptureCoordinator.setCapturing(true)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mgr.createScreenCaptureIntent())
    }

    companion object {
        private const val EXTRA_DELAY_MS = "extra_delay_ms"

        fun newIntent(context: Context, delayMs: Long = 500L): Intent {
            return Intent(context, ProjectionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_DELAY_MS, delayMs)
            }
        }
    }
}
