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
import com.xingshu.helper.data.repository.CorpusSyncManager
import com.xingshu.helper.data.repository.EmbeddingRepository
import com.xingshu.helper.data.repository.QACorpusLoader
import com.xingshu.helper.data.repository.QueryRouter
import com.xingshu.helper.data.repository.SnippetRepository
import com.xingshu.helper.data.repository.StructuredKnowledgeBase
import com.xingshu.helper.data.repository.VectorStore
import com.xingshu.helper.data.repository.VisionRepository
import com.xingshu.helper.service.CaptureCoordinator
import com.xingshu.helper.service.ProjectionRequestActivity
import kotlinx.coroutines.Job
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
    /** 金标语料云同步状态。 */
    val corpusSync: CorpusSyncManager.State = CorpusSyncManager.State.Idle,
    /** 当前账号本地已同步的金标版本号；0 表示尚未同步过（用 APK assets 兜底）。 */
    val corpusVersion: Int = 0,
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

    // 用户连点"生成"或"结合 AI"会触发并发协程，互相覆盖 generateState。
    // 每次开新生成前先取消上一个，保证只有最新一次的回调能写入状态。
    private var generateJob: Job? = null

    private val corpusCache = mutableMapOf<BusinessAccount, List<Pair<QAItem, FloatArray>>>()
    private val corpusSyncManager = CorpusSyncManager(appContext)
    private val qaCorpusLoader = QACorpusLoader(appContext)
    private val structuredKb = StructuredKnowledgeBase(appContext)

    private var currentStructuredContext: String = ""

    private fun invalidateCorpusCache(account: BusinessAccount) {
        corpusCache.remove(account)
    }

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
        val hadBasket = _state.value.basket.isNotEmpty()
        _state.update {
            it.copy(
                dialogMessages = messages,
                basket = emptyList(),
                visionState = VisionState.Idle
            )
        }
        val suffix = if (hadBasket) "（已清空本轮消息）" else ""
        showSnackbar("已识别 ${messages.size} 条对话（客户 $customerCount / 我 $meCount）$suffix")
    }

    private suspend fun loadCorpus(account: BusinessAccount) {
        try {
            val cached = corpusCache[account]
            val corpus = cached ?: qaCorpusLoader.load(account).also {
                corpusCache[account] = it
            }
            vectorStore.initialize(corpus)
            val version = corpusSyncManager.localVersion(account)
            _state.update { it.copy(corpusReady = true, corpusVersion = version) }
            val src = if (cached != null) "缓存" else "磁盘"
            android.util.Log.d("PanelViewModel", "RAG 语料库加载完成 [${account.key}]: ${corpus.size} 条（来自$src），本地金标版本 $version")
            currentStructuredContext = structuredKb.load(account)
            android.util.Log.d("PanelViewModel", "结构化知识库加载完成 [${account.key}]: ${currentStructuredContext.length} 字符")
            // 后台自动同步：拉到新版本静默 reload，UI 用 snackbar 提示
            autoSyncCorpus(account)
        } catch (e: Exception) {
            _state.update { it.copy(corpusReady = false) }
            android.util.Log.e("PanelViewModel", "RAG 语料库加载失败 [${account.key}]: ${e.message}")
        }
    }

    /** 启动后台静默同步金标，与用户操作并行；失败仅写日志，不打扰用户。 */
    private fun autoSyncCorpus(account: BusinessAccount) {
        if (!corpusSyncManager.isConfigured()) return
        viewModelScope.launch {
            val mgr = corpusSyncManager
            val ok = mgr.sync(account) { s -> _state.update { it.copy(corpusSync = s) } }
            val finalState = _state.value.corpusSync
            if (ok && finalState is CorpusSyncManager.State.Updated) {
                // 拉到了新版本 → 失效缓存 + 重新加载向量库 + snackbar
                invalidateCorpusCache(account)
                try {
                    val corpus = qaCorpusLoader.load(account)
                    corpusCache[account] = corpus
                    vectorStore.initialize(corpus)
                    currentStructuredContext = structuredKb.load(account)
                    _state.update {
                        it.copy(
                            corpusVersion = finalState.version,
                            snackbar = "金标库已更新到 v${finalState.version}（${finalState.count} 条）",
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PanelViewModel", "自动同步后 reload 失败：${e.message}")
                }
            }
        }
    }

    /** 用户在设置页点击"检查金标更新"时调用。同步成功后自动重新加载语料库。 */
    fun syncCorpus() {
        val account = _state.value.account
        viewModelScope.launch {
            val mgr = corpusSyncManager
            val ok = mgr.sync(account) { s ->
                _state.update { it.copy(corpusSync = s) }
            }
            if (ok && _state.value.corpusSync is CorpusSyncManager.State.Updated) {
                invalidateCorpusCache(account)
                loadCorpus(account)
            }
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

    /**
     * 默认入口：先判断是否结构化查询（价格/时段/地址）。
     * - 结构化查询 → 直接调 LLM + 结构化 KB，跳过 RAG
     * - 话术查询 → RAG 匹配，用户可在结果页点"结合 AI"再走 generateWithAi()
     */
    private fun doGenerate(messages: List<String>) {
        _state.update { it.copy(lastQuery = LastQuery.Messages(messages)) }
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            val query = messages.joinToString("\n")
            if (QueryRouter.route(query) == QueryRouter.RouteType.STRUCTURED) {
                doStructuredGenerate(messages = messages, dialog = null)
            } else {
                doRagOnly(query)
            }
        }
    }

    private fun doGenerateFromDialog(dialog: List<DialogMessage>) {
        _state.update { it.copy(lastQuery = LastQuery.Dialog(dialog)) }
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            // 检索时只用客户那边的话，否则会把"我"的旧回复混进 RAG query 影响相似度。
            // 只取最近 3 条：早期消息已处理完，拼进去会稀释最新问题的语义信号。
            val customerOnly = dialog.filter { it.role == DialogRole.CUSTOMER }
                .takeLast(3)
                .joinToString("\n") { it.text }
            if (QueryRouter.route(customerOnly) == QueryRouter.RouteType.STRUCTURED) {
                doStructuredGenerate(messages = null, dialog = dialog)
            } else {
                doRagOnly(customerOnly)
            }
        }
    }

    /**
     * 结构化查询路径：直接调 LLM，只注入结构化 KB，不走 RAG embedding/向量检索。
     * 如果结构化 KB 未加载（空），降级为话术 RAG 路径。
     */
    private suspend fun doStructuredGenerate(messages: List<String>?, dialog: List<DialogMessage>?) {
        if (currentStructuredContext.isBlank()) {
            val query = messages?.joinToString("\n")
                ?: dialog!!.filter { it.role == DialogRole.CUSTOMER }.takeLast(3).joinToString("\n") { it.text }
            android.util.Log.w("PanelViewModel", "结构化 KB 未加载，降级走 RAG")
            doRagOnly(query)
            return
        }
        android.util.Log.d("PanelViewModel", "结构化路径：跳过 RAG 直接调 LLM")
        val flow = if (messages != null) {
            aiRepository.generateStructured(messages, AppConfig.API_KEY, AppConfig.API_BASE_URL, currentStructuredContext)
        } else {
            aiRepository.generateStructuredFromDialog(dialog!!, AppConfig.API_KEY, AppConfig.API_BASE_URL, currentStructuredContext)
        }
        flow.collect { collectGenerateState(it) }
    }

    /** 结果页"结合 AI"按钮触发：复用刚才已检索好的 RAG 上下文（不再调 embedding API），调 LLM 生成三版回复。 */
    fun generateWithAi() {
        val query = _state.value.lastQuery ?: return
        // 直接复用 state.referencedQas 里的 RAG 命中，省去再次 embedding 查询
        val contextItems = _state.value.referencedQas.map { it.item }
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            when (query) {
                is LastQuery.Messages -> aiRepository.generate(
                    query.messages, AppConfig.API_KEY, AppConfig.API_BASE_URL,
                    contextItems, currentStructuredContext
                ).collect { collectGenerateState(it) }
                is LastQuery.Dialog -> aiRepository.generateFromDialog(
                    query.dialog, AppConfig.API_KEY, AppConfig.API_BASE_URL,
                    contextItems, currentStructuredContext
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
        _state.update { it.copy(referencedQas = hits.map { (item, score) -> ReferencedQa(item, score) }) }
        return hits
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

    /** 让 UI 层在外部触发提示（如"填入微信"按钮的反馈）。 */
    fun postSnackbar(msg: String) = showSnackbar(msg)

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
