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
 * Why: MediaProjection 授权弹窗只能从 Activity 触发；悬浮窗服务无法直接拉起。让面板按钮通过
 * NEW_TASK 启动这个透明 Activity，用户授权后立刻消失，体验上"几乎没看到 Activity"。
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Toast.makeText(
                this,
                "已授权，3 秒后自动截屏，请立即切到要识别的对话",
                Toast.LENGTH_LONG
            ).show()
            val svc = ScreenCaptureService.newIntent(
                this, result.resultCode, result.data!!,
                delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 3000L)
            )
            startForegroundService(svc)
        } else {
            CaptureCoordinator.setCapturing(false)
            Toast.makeText(this, "未授权截屏", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CaptureCoordinator.setCapturing(true)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mgr.createScreenCaptureIntent())
    }

    companion object {
        private const val EXTRA_DELAY_MS = "extra_delay_ms"

        fun newIntent(context: Context, delayMs: Long = 3000L): Intent {
            return Intent(context, ProjectionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_DELAY_MS, delayMs)
            }
        }
    }
}
