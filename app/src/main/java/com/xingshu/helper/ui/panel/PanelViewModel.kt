package com.xingshu.helper.ui.panel

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xingshu.helper.AppConfig
import com.xingshu.helper.data.account.AccountManager
import com.xingshu.helper.data.account.BusinessAccount
import com.xingshu.helper.data.model.BasketMessage
import com.xingshu.helper.data.model.DialogMessage
import com.xingshu.helper.data.model.DialogRole
import com.xingshu.helper.data.model.GenerateState
import com.xingshu.helper.data.model.PanelScreen
import com.xingshu.helper.data.model.VisionState
import com.xingshu.helper.data.repository.AIRepository
import com.xingshu.helper.data.repository.EmbeddingRepository
import com.xingshu.helper.data.repository.QACorpusLoader
import com.xingshu.helper.data.repository.VectorStore
import com.xingshu.helper.data.repository.VisionRepository
import com.xingshu.helper.service.CaptureCoordinator
import com.xingshu.helper.service.ProjectionRequestActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PanelUiState(
    val clipboardPreview: String = "",
    val clipboardStatus: ClipboardStatus = ClipboardStatus.EMPTY,
    val basket: List<BasketMessage> = emptyList(),
    val generateState: GenerateState = GenerateState.Idle,
    val currentScreen: PanelScreen = PanelScreen.MAIN,
    val snackbar: String? = null,
    val dialogMessages: List<DialogMessage> = emptyList(),
    val visionState: VisionState = VisionState.Idle,
    val account: BusinessAccount = BusinessAccount.KIRIN,
    val corpusReady: Boolean = false,
)

enum class ClipboardStatus { EMPTY, OK, DUPLICATE, TOO_LONG }

sealed class PanelEvent {
    data object HidePanel : PanelEvent()
    data object ShowPanel : PanelEvent()
}

class PanelViewModel(
    private val aiRepository: AIRepository,
    private val visionRepository: VisionRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val vectorStore: VectorStore,
    private val accountManager: AccountManager,
    private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(PanelUiState(account = accountManager.current.value))
    val uiState: StateFlow<PanelUiState> = _state

    private val _events = MutableSharedFlow<PanelEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PanelEvent> = _events

    init {
        // 监听账号切换，每次切换都重新加载对应语料库
        viewModelScope.launch {
            accountManager.current.collect { account ->
                _state.update { it.copy(account = account, corpusReady = false) }
                loadCorpus(account)
            }
        }

        // 监听 ScreenCaptureService 的截屏结果，自动跑 OCR 并写入对话状态
        viewModelScope.launch {
            CaptureCoordinator.events.collect { event ->
                when (event) {
                    is CaptureCoordinator.Event.Success -> runOcr(event.bitmap)
                    is CaptureCoordinator.Event.Error -> {
                        _state.update { it.copy(visionState = VisionState.Error(event.message)) }
                        _events.tryEmit(PanelEvent.ShowPanel)
                        showSnackbar("截屏失败：${event.message}")
                    }
                }
            }
        }
    }

    fun startScreenCapture() {
        // 先关掉面板，让面板下面的微信对话暴露给截屏
        _events.tryEmit(PanelEvent.HidePanel)

        if (CaptureCoordinator.hasActiveProjection.value) {
            // 已授权过：直接复用 projection，500ms 延时只是给面板隐藏留点缓冲
            CaptureCoordinator.setCapturing(true)
            val intent = com.xingshu.helper.service.ScreenCaptureService
                .newCaptureAgainIntent(appContext, delayMs = 500L)
            appContext.startForegroundService(intent)
        } else {
            // 首次或 projection 已失效：走授权流程
            appContext.startActivity(ProjectionRequestActivity.newIntent(appContext, delayMs = 500L))
        }
    }

    private fun runOcr(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch {
            visionRepository.extractDialog(bitmap).collect { vs ->
                _state.update { it.copy(visionState = vs) }
                when (vs) {
                    is VisionState.Success -> {
                        applyDialogMessages(vs.messages)
                        _events.tryEmit(PanelEvent.ShowPanel)
                    }
                    is VisionState.Error -> {
                        _events.tryEmit(PanelEvent.ShowPanel)
                        showSnackbar("识别失败：${vs.message}")
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun applyDialogMessages(messages: List<DialogMessage>) {
        val customerCount = messages.count { it.role == DialogRole.CUSTOMER }
        val meCount = messages.count { it.role == DialogRole.ME }
        android.util.Log.d(
            "PanelViewModel",
            "applyDialogMessages: total=${messages.size}, customer=$customerCount, me=$meCount"
        )
        if (messages.isEmpty()) {
            _state.update { it.copy(dialogMessages = emptyList(), visionState = VisionState.Idle) }
            showSnackbar("未识别到对话内容（截图可能未抓到聊天界面）")
            return
        }
        // OCR 模式：dialogMessages 是单一真源，UI 直接渲染气泡
        // 清空 basket 是为了让 UI 切到 dialog 视图，不再显示残留的剪贴板条目
        _state.update {
            it.copy(
                dialogMessages = messages,
                basket = emptyList(),
                visionState = VisionState.Idle
            )
        }
        showSnackbar("已识别 ${messages.size} 条对话（客户 $customerCount / 我 $meCount）")
    }

    private suspend fun loadCorpus(account: BusinessAccount) {
        try {
            val corpus = QACorpusLoader(appContext).load(account)
            vectorStore.initialize(corpus)
            _state.update { it.copy(corpusReady = true) }
            android.util.Log.d("PanelViewModel", "RAG 语料库加载完成 [${account.key}]: ${corpus.size} 条")
        } catch (e: Exception) {
            _state.update { it.copy(corpusReady = false) }
            android.util.Log.e("PanelViewModel", "RAG 语料库加载失败 [${account.key}]: ${e.message}")
        }
    }

    fun switchAccount(account: BusinessAccount) {
        if (account == accountManager.current.value) return
        accountManager.set(account)
        showSnackbar("已切换到 ${account.displayName}，正在加载话术库…")
    }

    fun clearVisionState() {
        _state.update { it.copy(visionState = VisionState.Idle, dialogMessages = emptyList()) }
    }

    /** 用户手动删除某条 OCR 出来的对话（索引基础） */
    fun removeDialogMessage(index: Int) {
        _state.update { state ->
            val list = state.dialogMessages
            if (index !in list.indices) state else state.copy(
                dialogMessages = list.toMutableList().apply { removeAt(index) }
            )
        }
    }

    /** 清空 OCR 对话上下文，回到剪贴板手动模式 */
    fun clearDialog() {
        _state.update { it.copy(dialogMessages = emptyList(), basket = emptyList()) }
    }

    fun readClipboard() {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""

        if (text.isBlank()) {
            _state.update { it.copy(clipboardPreview = "", clipboardStatus = ClipboardStatus.EMPTY) }
            return
        }

        val isDuplicate = _state.value.basket.any { it.content == text }
        val status = when {
            isDuplicate -> ClipboardStatus.DUPLICATE
            text.length > 300 -> ClipboardStatus.TOO_LONG
            else -> ClipboardStatus.OK
        }
        _state.update { it.copy(clipboardPreview = text, clipboardStatus = status) }
    }

    fun addToBasket() {
        val text = _state.value.clipboardPreview
        if (text.isBlank()) return
        if (_state.value.basket.size >= 10) {
            showSnackbar("最多收集 10 条消息")
            return
        }
        if (_state.value.basket.any { it.content == text }) {
            showSnackbar("该内容已加入本轮")
            return
        }
        _state.update { state ->
            state.copy(basket = state.basket + BasketMessage(content = text))
        }
        showSnackbar("已加入本轮（共 ${_state.value.basket.size} 条）")
    }

    fun removeFromBasket(id: Long) {
        _state.update { it.copy(basket = it.basket.filter { msg -> msg.id != id }) }
    }

    fun clearBasket() {
        _state.update { it.copy(basket = emptyList()) }
    }

    fun generateSingle() {
        // 有 OCR 对话上下文优先走对话路径（带"我"的历史回复），更准确
        val dialog = _state.value.dialogMessages
        if (dialog.isNotEmpty()) {
            doGenerateFromDialog(dialog)
            return
        }
        val text = _state.value.clipboardPreview
        if (text.isBlank()) {
            showSnackbar("剪贴板为空，请先在微信复制客户消息")
            return
        }
        doGenerate(listOf(text))
    }

    fun generateFromBasket() {
        // 有 OCR 对话上下文优先走对话路径
        val dialog = _state.value.dialogMessages
        if (dialog.isNotEmpty()) {
            doGenerateFromDialog(dialog)
            return
        }
        val messages = _state.value.basket.map { it.content }
        if (messages.isEmpty()) {
            showSnackbar("本轮还没有收集消息")
            return
        }
        doGenerate(messages)
    }

    private fun doGenerate(messages: List<String>) {
        viewModelScope.launch {
            val contextItems = retrieveContext(messages.joinToString("\n"))
            aiRepository.generate(messages, AppConfig.API_KEY, AppConfig.API_BASE_URL, contextItems)
                .collect { collectGenerateState(it) }
        }
    }

    private fun doGenerateFromDialog(dialog: List<DialogMessage>) {
        viewModelScope.launch {
            // 检索时只用客户那边的话，否则会把"我"的旧回复混进 RAG query 影响相似度
            val customerOnly = dialog.filter { it.role == DialogRole.CUSTOMER }
                .joinToString("\n") { it.text }
            val contextItems = retrieveContext(customerOnly)
            aiRepository.generateFromDialog(
                dialog, AppConfig.API_KEY, AppConfig.API_BASE_URL, contextItems
            ).collect { collectGenerateState(it) }
        }
    }

    private suspend fun retrieveContext(query: String): List<com.xingshu.helper.data.model.QAItem> {
        if (!vectorStore.isReady || query.isBlank()) return emptyList()
        val queryVec = embeddingRepository.embed(query, AppConfig.API_KEY, AppConfig.API_BASE_URL)
            ?: return emptyList()
        return vectorStore.search(queryVec, topK = 5).map { (item, _) -> item }
    }

    private fun collectGenerateState(state: GenerateState) {
        _state.update { it.copy(generateState = state) }
        if (state is GenerateState.Success || state is GenerateState.Error) {
            _state.update { it.copy(currentScreen = PanelScreen.RESULT) }
        }
    }

    fun navigateTo(screen: PanelScreen) {
        _state.update { it.copy(currentScreen = screen) }
    }

    fun clearSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    private fun showSnackbar(msg: String) {
        _state.update { it.copy(snackbar = msg) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return PanelViewModel(
                AIRepository(),
                VisionRepository(),
                EmbeddingRepository(),
                VectorStore(),
                AccountManager(context.applicationContext),
                context.applicationContext
            ) as T
        }
    }
}
