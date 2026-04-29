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

    // VirtualDisplay + ImageReader 长驻：projection 建立后一直 mirror 屏幕，
    // 不在每次截屏后释放 —— 否则 HyperOS / MIUI 等 ROM 会判定 projection 闲置
    // 而自动撤销，导致下次必须重新授权
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // 是否有"待响应的截屏请求"。listener 持续收到帧，没请求时 drain 丢弃，
    // 有请求时取下一帧返回
    @Volatile private var pendingCapture: Boolean = false
    @Volatile private var skipFramesRemaining: Int = 0

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
                releaseDisplayAndReader()
                CaptureCoordinator.setActiveProjection(false)
            }
        }, handler)

        projection = proj
        CaptureCoordinator.setActiveProjection(true)

        // 一次性建立长驻的 VirtualDisplay + ImageReader，全程不释放
        if (!setupDisplayAndReader(proj)) return
        Log.d(TAG, "projection + display 建立完成，scheduling first capture in ${delayMs}ms")

        // 安排首次截屏
        scheduleNextCapture(delayMs, isFirstCapture = true)
    }

    private fun handleCaptureAgain(intent: Intent) {
        val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, DEFAULT_DELAY_MS)
        if (projection == null || virtualDisplay == null || imageReader == null) {
            postError("MediaProjection 已失效，请重新授权")
            CaptureCoordinator.setActiveProjection(false)
            return
        }
        Log.d(TAG, "reusing projection + display, scheduling capture in ${delayMs}ms")
        scheduleNextCapture(delayMs, isFirstCapture = false)
    }

    /**
     * 建立持续运行的 VirtualDisplay 和 ImageReader。listener 每帧都被触发：
     * - pendingCapture=false：drain 丢弃帧，避免 buffer 溢出
     * - pendingCapture=true：取一帧处理后 reset 标志位
     */
    private fun setupDisplayAndReader(proj: MediaProjection): Boolean {
        val h = handler ?: return false
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h2 = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(w, h2, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            // 不在响应窗口内：丢弃帧（必须 close 否则 buffer 锁死）
            if (!pendingCapture) {
                img.close()
                return@setOnImageAvailableListener
            }
            // 在响应窗口：先消耗"跳过"配额（VirtualDisplay 刚建/或刚收到隐藏面板事件后头一两帧可能是过渡帧）
            if (skipFramesRemaining > 0) {
                skipFramesRemaining--
                img.close()
                return@setOnImageAvailableListener
            }
            // 真正处理：拿这一帧、置标志位、释放等待下次
            pendingCapture = false
            try {
                val bitmap = imageToBitmap(img, w, h2)
                val sig = bitmapSignature(bitmap)
                val path = debugSaveBitmap(bitmap)
                Log.d(
                    TAG,
                    "captured ${bitmap.width}x${bitmap.height} " +
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
                CaptureCoordinator.setCapturing(false)
            }
        }, h)

        return try {
            virtualDisplay = proj.createVirtualDisplay(
                "xingshu-capture", w, h2, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, h
            )
            imageReader = reader
            true
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            postError("创建虚拟显示失败：${e.message}")
            try { reader.close() } catch (_: Exception) {}
            false
        }
    }

    /**
     * 安排下一次截屏：等 delayMs（让面板隐藏 + 给屏幕一个稳定窗口），然后开 capture window，
     * 跳过前 N 帧（防止过渡帧），抓下一帧返回。
     */
    private fun scheduleNextCapture(delayMs: Long, isFirstCapture: Boolean) {
        handler?.postDelayed({
            // 跳帧策略：首次截屏（VirtualDisplay 刚建立）多跳几帧；后续直接抓
            skipFramesRemaining = if (isFirstCapture) 1 else 0
            pendingCapture = true
            Log.d(TAG, "capture window opened, skipFrames=$skipFramesRemaining")
            // 兜底：如果 5 秒内屏幕完全静止没有新帧到来，强制取一次 latest
            handler?.postDelayed({
                if (pendingCapture) {
                    val img = imageReader?.acquireLatestImage()
                    if (img != null) {
                        Log.d(TAG, "force-grabbing static frame")
                        // 复用 listener 路径：把 pendingCapture 留 true，listener 下次会直接处理
                        // 这里为了简单直接处理
                        pendingCapture = false
                        try {
                            val w = imageReader!!.width
                            val h = imageReader!!.height
                            val bitmap = imageToBitmap(img, w, h)
                            CaptureCoordinator.postSuccess(bitmap)
                            debugSaveBitmap(bitmap)
                        } finally {
                            img.close()
                            CaptureCoordinator.setCapturing(false)
                        }
                    } else {
                        postError("等待截屏帧超时")
                    }
                }
            }, FORCE_GRAB_TIMEOUT_MS)
        }, delayMs)
    }

    private fun releaseDisplayAndReader() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        pendingCapture = false
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
        releaseDisplayAndReader()
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
        private const val FORCE_GRAB_TIMEOUT_MS = 1_500L

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
