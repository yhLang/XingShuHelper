package com.xingshu.helper.service

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 进程级单例，承担两件事：
 *   1. 在 ScreenCaptureService 与 UI（Activity / ViewModel）之间传递截屏结果
 *   2. 暴露当前 MediaProjection 是否仍有效，让调用方可以决定走"复用"还是"重新授权"
 *
 * 用 SharedFlow 是为了支持多消费者（PanelViewModel 主消费 + MainActivity POC 调试），
 * 每个订阅者都能看到事件。
 */
object CaptureCoordinator {

    sealed class Event {
        data class Success(val bitmap: Bitmap) : Event()
        data class Error(val message: String) : Event()
    }

    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events

    fun postSuccess(bitmap: Bitmap) {
        _events.tryEmit(Event.Success(bitmap))
    }

    fun postError(message: String) {
        _events.tryEmit(Event.Error(message))
    }

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    fun setCapturing(value: Boolean) {
        _isCapturing.value = value
    }

    /** ScreenCaptureService 当前是否持有有效的 MediaProjection（可以复用免授权） */
    private val _hasActiveProjection = MutableStateFlow(false)
    val hasActiveProjection: StateFlow<Boolean> = _hasActiveProjection

    fun setActiveProjection(value: Boolean) {
        _hasActiveProjection.value = value
    }
}
