package com.xingshu.helper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 一次性截屏服务。生命周期短：启动 → 拉起 MediaProjection → 延迟若干毫秒 → 抓一帧 → 通过
 * CaptureCoordinator 投递 Bitmap → stopSelf。
 *
 * Why: Android 14+ 要求 getMediaProjection 之前必须有 mediaProjection 类型的前台服务在运行。
 * 把它独立成一个短命服务而不是塞进 FloatingBallService，是因为：
 *   1. 截屏权限敏感，按需启用减少长期持有
 *   2. 隔离 MediaProjection 失效回调，不会影响悬浮球
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var captured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfWithError("启动参数缺失")
            return START_NOT_STICKY
        }

        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS)

        if (data == null || resultCode == 0) {
            stopSelfWithError("授权数据缺失")
            return START_NOT_STICKY
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            stopSelfWithError("获取 MediaProjection 失败：${e.message}")
            return START_NOT_STICKY
        }
        projection = proj

        val ht = HandlerThread("xingshu-capture").apply { start() }
        handlerThread = ht
        val handler = Handler(ht.looper)

        // Android 14+ 要求 createVirtualDisplay 前必须注册 Callback
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection.onStop")
            }
        }, handler)

        handler.postDelayed({ doCapture(proj, handler) }, delayMs)
        return START_NOT_STICKY
    }

    private fun doCapture(proj: MediaProjection, handler: Handler) {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            if (captured) return@setOnImageAvailableListener
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            captured = true
            try {
                val bitmap = imageToBitmap(img, w, h)
                CaptureCoordinator.postSuccess(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "imageToBitmap failed", e)
                CaptureCoordinator.postError("截屏转 Bitmap 失败：${e.message}")
            } finally {
                img.close()
                cleanup()
                stopSelf()
            }
        }, handler)

        try {
            virtualDisplay = proj.createVirtualDisplay(
                "xingshu-capture", w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            CaptureCoordinator.postError("创建虚拟显示失败：${e.message}")
            cleanup()
            stopSelf()
        }
    }

    private fun imageToBitmap(img: android.media.Image, w: Int, h: Int): Bitmap {
        val planes = img.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * w
        val rowPaddedW = w + rowPadding / pixelStride
        val raw = Bitmap.createBitmap(rowPaddedW, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) raw else Bitmap.createBitmap(raw, 0, 0, w, h)
    }

    private fun stopSelfWithError(msg: String) {
        Log.w(TAG, "stopSelfWithError: $msg")
        CaptureCoordinator.postError(msg)
        cleanup()
        stopSelf()
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
        CaptureCoordinator.setCapturing(false)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "屏幕识别", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在识别屏幕")
            .setContentText("助手正在抓取当前屏幕内容用于客服回复生成")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "xingshu_capture"
        private const val NOTIFICATION_ID = 2
        private const val DEFAULT_DELAY_MS = 3_000L

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_DELAY_MS = "extra_delay_ms"

        fun newIntent(context: Context, resultCode: Int, data: Intent, delayMs: Long): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_DELAY_MS, delayMs)
            }
        }
    }
}
