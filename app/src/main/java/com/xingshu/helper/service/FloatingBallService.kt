package com.xingshu.helper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xingshu.helper.MainActivity
import com.xingshu.helper.R
import com.xingshu.helper.ui.panel.FloatingBallView
import com.xingshu.helper.ui.panel.FloatingPanelRoot
import com.xingshu.helper.ui.panel.PanelEvent
import com.xingshu.helper.ui.panel.PanelViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingBallService : Service(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val viewModelStore: ViewModelStore = store
    override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var ballView: ComposeView? = null
    private var panelView: ComposeView? = null

    private var ballX = 0
    private var ballY = 600
    private var isPanelVisible = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventsJob: Job? = null

    private val viewModel: PanelViewModel by lazy {
        ViewModelProvider(this, PanelViewModel.Factory(this))[PanelViewModel::class.java]
    }

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createBall()

        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // 监听 ViewModel 发出的面板显隐事件（截屏前隐藏、OCR 完成后弹回）
        eventsJob = serviceScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is PanelEvent.HidePanel -> if (isPanelVisible) removePanel()
                    is PanelEvent.ShowPanel -> if (!isPanelVisible) showPanel()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        eventsJob?.cancel()
        serviceScope.cancel()
        removeBall()
        removePanel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Ball ──────────────────────────────────────────────────────────────

    private fun createBall() {
        ballView = makeComposeView {
            FloatingBallView(
                onClick = { togglePanel() }
            )
        }

        val params = ballLayoutParams()
        windowManager.addView(ballView, params)

        ballView!!.setOnTouchListener(DragTouchListener(params) { x, y ->
            ballX = x
            ballY = y
        })
    }

    private fun removeBall() {
        ballView?.let { windowManager.removeViewImmediate(it) }
        ballView = null
    }

    // ── Panel ─────────────────────────────────────────────────────────────

    private fun togglePanel() {
        if (isPanelVisible) removePanel() else showPanel()
    }

    private fun showPanel() {
        if (isPanelVisible) return
        isPanelVisible = true

        panelView = makeComposeView {
            FloatingPanelRoot(
                viewModel = viewModel,
                onClose = { removePanel() }
            )
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (screenHeight * 0.82f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            dimAmount = 0.4f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        windowManager.addView(panelView, params)
    }

    private fun removePanel() {
        panelView?.let { windowManager.removeViewImmediate(it) }
        panelView = null
        isPanelVisible = false
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun makeComposeView(content: @androidx.compose.runtime.Composable () -> Unit): ComposeView {
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@FloatingBallService)
            setViewTreeViewModelStoreOwner(this@FloatingBallService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBallService)
            setContent { content() }
        }
    }

    private fun ballLayoutParams(): WindowManager.LayoutParams {
        val size = (56 * resources.displayMetrics.density).toInt()
        return WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "xingshu_overlay"
    }

    // ── Drag listener ─────────────────────────────────────────────────────

    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams,
        private val onPositionChanged: (Int, Int) -> Unit
    ) : android.view.View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            return when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (abs(dx) > 5 || abs(dy) > 5) moved = true
                    if (moved) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        windowManager.updateViewLayout(ballView, params)
                        onPositionChanged(params.x, params.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }
                else -> false
            }
        }
    }
}
