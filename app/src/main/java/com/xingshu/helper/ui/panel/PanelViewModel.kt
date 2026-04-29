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
import com.xingshu.helper.data.model.GeneratedResult
import com.xingshu.helper.data.model.GenerateState
import com.xingshu.helper.data.model.PanelScreen
import com.xingshu.helper.data.model.QAItem
import com.xingshu.helper.data.model.RagMatch
import com.xingshu.helper.data.model.Snippet
import com.xingshu.helper.data.model.VisionState
import com.xingshu.helper.data.repository.AIRepository
import com.xingshu.helper.data.repository.EmbeddingRepository
import com.xingshu.helper.data.repository.QACorpusLoader
import com.xingshu.helper.data.repository.SnippetRepository
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
    /** 上一次生成时检索到的参考话术（按相似度降序），用于结果页"参考来源"展示。 */
    val referencedQas: List<ReferencedQa> = emptyList(),
    /** 当前账号的常用片段（不走 RAG，静态加载）。 */
    val snippets: List<Snippet> = emptyList(),
    /** 上一次生成请求的输入，用于结果页"结合 AI 重跑"按钮。 */
    val lastQuery: LastQuery? = null,
)

/** 暂存最近一次生成的输入，便于用户在结果页选择"结合 AI 重跑"。 */
sealed class LastQuery {
    data class Messages(val messages: List<String>) : LastQuery()
    data class Dialog(val dialog: List<DialogMessage>) : LastQuery()
}

/** 一条 RAG 检索结果，带相似度分数（0-1，越大越相似）。 */
data class ReferencedQa(val item: QAItem, val score: Float)

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
        // 监听账号切换，每次切换都重新加载对应语料库 + 常用片段
        viewModelScope.launch {
            accountManager.current.collect { account ->
                _state.update { it.copy(account = account, corpusReady = false, snippets = emptyList()) }
                loadCorpus(account)
                loadSnippets(account)
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

    private suspend fun loadSnippets(account: BusinessAccount) {
        val list = SnippetRepository(appContext).load(account)
        _state.update { it.copy(snippets = list) }
        android.util.Log.d("PanelViewModel", "常用片段加载完成 [${account.key}]: ${list.size} 条")
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

    /** 默认入口：直接 RAG 匹配，不调 LLM。结果页上若用户不满意可点"结合 AI"按钮再走 generateWithAi()。 */
    private fun doGenerate(messages: List<String>) {
        _state.update { it.copy(lastQuery = LastQuery.Messages(messages)) }
        viewModelScope.launch {
            doRagOnly(messages.joinToString("\n"))
        }
    }

    private fun doGenerateFromDialog(dialog: List<DialogMessage>) {
        _state.update { it.copy(lastQuery = LastQuery.Dialog(dialog)) }
        viewModelScope.launch {
            // 检索时只用客户那边的话，否则会把"我"的旧回复混进 RAG query 影响相似度
            val customerOnly = dialog.filter { it.role == DialogRole.CUSTOMER }
                .joinToString("\n") { it.text }
            doRagOnly(customerOnly)
        }
    }

    /** 结果页"结合 AI"按钮触发：复用刚才已检索好的 RAG 上下文（不再调 embedding API），调 LLM 生成三版回复。 */
    fun generateWithAi() {
        val query = _state.value.lastQuery ?: return
        // 直接复用 state.referencedQas 里的 RAG 命中，省去再次 embedding 查询
        val contextItems = _state.value.referencedQas.map { it.item }
        viewModelScope.launch {
            when (query) {
                is LastQuery.Messages -> aiRepository.generate(
                    query.messages, AppConfig.API_KEY, AppConfig.API_BASE_URL, contextItems
                ).collect { collectGenerateState(it) }
                is LastQuery.Dialog -> aiRepository.generateFromDialog(
                    query.dialog, AppConfig.API_KEY, AppConfig.API_BASE_URL, contextItems
                ).collect { collectGenerateState(it) }
            }
        }
    }

    private suspend fun doRagOnly(query: String) {
        _state.update { it.copy(generateState = GenerateState.Loading) }
        val matches = retrieveContextWithScores(query)
        if (matches.isEmpty()) {
            collectGenerateState(GenerateState.Error("未找到匹配的历史回答，请检查语料库是否加载"))
            return
        }
        val ragMatches = matches.map { (item, score) ->
            RagMatch(scene = item.scene, answer = item.answer, score = score)
        }
        collectGenerateState(GenerateState.Success(GeneratedResult(isDirectMatch = true, ragMatches = ragMatches)))
    }

    private suspend fun retrieveContextWithScores(query: String): List<Pair<QAItem, Float>> {
        if (!vectorStore.isReady || query.isBlank()) {
            _state.update { it.copy(referencedQas = emptyList()) }
            return emptyList()
        }
        val queryVec = embeddingRepository.embed(query, AppConfig.API_KEY, AppConfig.API_BASE_URL)
        if (queryVec == null) {
            _state.update { it.copy(referencedQas = emptyList()) }
            return emptyList()
        }
        val hits = vectorStore.search(queryVec, topK = 5)
        // 把检索结果（带分数）写入 state，RAG+AI 模式下结果页展示「参考话术」面板
        _state.update { it.copy(referencedQas = hits.map { (item, score) -> ReferencedQa(item, score) }) }
        return hits
    }

    private suspend fun retrieveContext(query: String): List<QAItem> {
        return retrieveContextWithScores(query).map { (item, _) -> item }
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
