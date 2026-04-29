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
import java.io.File
import java.io.FileOutputStream

/**
 * 长驻的截屏服务。一次授权 -> 多次截屏复用同一个 MediaProjection 实例。
 *
 * 命令模式（通过 Intent action 区分）：
 *   - ACTION_START_AND_CAPTURE：携带 resultCode/data，建立 projection 并立即排一次截屏
 *   - ACTION_CAPTURE_AGAIN：复用已有 projection 排一次截屏（免授权）
 *   - ACTION_STOP：主动释放 projection，停止服务
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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

        val ht = HandlerThread("xingshu-capture").apply { start() }
        handlerThread = ht
        handler = Handler(ht.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AND_CAPTURE -> handleStartAndCapture(intent)
            ACTION_CAPTURE_AGAIN -> handleCaptureAgain(intent)
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP")
                cleanupProjection()
                stopSelf()
            }
            else -> Log.w(TAG, "unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    private fun handleStartAndCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS)

        if (data == null || resultCode == 0) {
            postError("授权数据缺失")
            return
        }

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mgr.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            postError("获取 MediaProjection 失败：${e.message}")
            return
        }

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection.onStop (用户撤销或系统终止)")
                projection = null
                CaptureCoordinator.setActiveProjection(false)
            }
        }, handler)

        projection = proj
        CaptureCoordinator.setActiveProjection(true)
        Log.d(TAG, "projection established, scheduling capture in ${delayMs}ms")

        handler?.postDelayed({ captureOne() }, delayMs)
    }

    private fun handleCaptureAgain(intent: Intent) {
        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS)
        if (projection == null) {
            postError("MediaProjection 已失效，请重新授权")
            CaptureCoordinator.setActiveProjection(false)
            return
        }
        Log.d(TAG, "reusing projection, scheduling capture in ${delayMs}ms")
        handler?.postDelayed({ captureOne() }, delayMs)
    }

    private fun captureOne() {
        val proj = projection
        val h = handler
        if (proj == null || h == null) {
            postError("服务状态异常，无法截屏")
            return
        }

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h2 = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(w, h2, PixelFormat.RGBA_8888, 3)
        var virtualDisplay: VirtualDisplay? = null
        var captured = false
        var frameCount = 0

        reader.setOnImageAvailableListener({ r ->
            if (captured) return@setOnImageAvailableListener
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            frameCount++
            // 跳过第一帧（VirtualDisplay 刚建立时画面可能未稳定 / 黑屏）
            if (frameCount < 2) {
                img.close()
                return@setOnImageAvailableListener
            }
            captured = true
            try {
                val bitmap = imageToBitmap(img, w, h2)
                val sig = bitmapSignature(bitmap)
                val path = debugSaveBitmap(bitmap)
                Log.d(
                    TAG,
                    "captured frame#$frameCount ${bitmap.width}x${bitmap.height} " +
                        "centerPixel=0x${Integer.toHexString(sig.sampleCenter)} " +
                        "allSameColor=${sig.isAllSameColor} savedTo=$path"
                )
                if (sig.isAllSameColor) {
                    CaptureCoordinator.postError(
                        "截到的是纯色画面（可能是黑屏 / 面板未隐藏），cap=$path"
                    )
                } else {
                    CaptureCoordinator.postSuccess(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "imageToBitmap failed", e)
                CaptureCoordinator.postError("截屏转 Bitmap 失败：${e.message}")
            } finally {
                img.close()
                try { virtualDisplay?.release() } catch (_: Exception) {}
                try { reader.close() } catch (_: Exception) {}
                CaptureCoordinator.setCapturing(false)
            }
        }, h)

        try {
            virtualDisplay = proj.createVirtualDisplay(
                "xingshu-capture", w, h2, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, h
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            postError("创建虚拟显示失败：${e.message}")
            try { reader.close() } catch (_: Exception) {}
        }
    }

    private fun debugSaveBitmap(bitmap: Bitmap): String? {
        // 存到 externalCacheDir，路径形如 /sdcard/Android/data/<pkg>/cache/captures/
        // 用户可直接通过文件管理器或 `adb pull` 取出，无需 root
        return try {
            val baseDir = externalCacheDir ?: cacheDir
            val dir = File(baseDir, "captures").apply { mkdirs() }
            val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            Log.d(TAG, "saved debug capture: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "saveDebugBitmap failed", e)
            null
        }
    }

    /** 简单签名：采样几个像素点，判断是否纯色（全黑/全白） */
    private data class BitmapSig(val isAllSameColor: Boolean, val sampleCenter: Int)

    private fun bitmapSignature(bitmap: Bitmap): BitmapSig {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return BitmapSig(true, 0)
        val samples = listOf(
            bitmap.getPixel(w / 4, h / 4),
            bitmap.getPixel(w / 2, h / 2),
            bitmap.getPixel(3 * w / 4, h / 2),
            bitmap.getPixel(w / 2, 3 * h / 4),
            bitmap.getPixel(w / 4, 3 * h / 4)
        )
        val allSame = samples.toSet().size == 1
        return BitmapSig(allSame, bitmap.getPixel(w / 2, h / 2))
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

    private fun postError(msg: String) {
        Log.w(TAG, "postError: $msg")
        CaptureCoordinator.postError(msg)
        CaptureCoordinator.setCapturing(false)
    }

    private fun cleanupProjection() {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        CaptureCoordinator.setActiveProjection(false)
    }

    override fun onDestroy() {
        cleanupProjection()
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
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
            .setContentTitle("行恕助手 · 屏幕识别就绪")
            .setContentText("已授权截屏，下次识别可直接复用，无需再次确认")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "xingshu_capture"
        private const val NOTIFICATION_ID = 2
        private const val DEFAULT_DELAY_MS = 5_000L

        const val ACTION_START_AND_CAPTURE = "com.xingshu.helper.START_AND_CAPTURE"
        const val ACTION_CAPTURE_AGAIN = "com.xingshu.helper.CAPTURE_AGAIN"
        const val ACTION_STOP = "com.xingshu.helper.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_DELAY_MS = "extra_delay_ms"

        fun newStartIntent(context: Context, resultCode: Int, data: Intent, delayMs: Long): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START_AND_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_DELAY_MS, delayMs)
            }
        }

        fun newCaptureAgainIntent(context: Context, delayMs: Long): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_CAPTURE_AGAIN
                putExtra(EXTRA_DELAY_MS, delayMs)
            }
        }

        fun newStopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        }

        // 旧入口保留，给 MainActivity 调试用（首次授权 + 截屏）
        fun newIntent(context: Context, resultCode: Int, data: Intent, delayMs: Long): Intent =
            newStartIntent(context, resultCode, data, delayMs)
    }
}
