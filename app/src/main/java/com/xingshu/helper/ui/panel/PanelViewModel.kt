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
import com.xingshu.helper.data.repository.GoldUploader
import com.xingshu.helper.data.repository.LocalGoldStore
import com.xingshu.helper.data.repository.QACorpusLoader
import com.xingshu.helper.data.repository.SnippetRepository
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
    /** 添加金标 QA 流程的状态。 */
    val addGold: AddGoldState = AddGoldState(),
    /** 金标语料云同步状态。 */
    val corpusSync: CorpusSyncManager.State = CorpusSyncManager.State.Idle,
    /** 当前账号本地已同步的金标版本号；0 表示尚未同步过（用 APK assets 兜底）。 */
    val corpusVersion: Int = 0,
)

/** 添加金标 QA 表单状态。 */
data class AddGoldState(
    val scene: String = "",
    val answer: String = "",
    val riskNote: String = "",
    /** AI 反推出的 Q 候选，用户可编辑/删除。 */
    val questionDrafts: List<String> = emptyList(),
    /** 当前正在调 LLM 生成 Q 候选 */
    val generating: Boolean = false,
    /** 当前正在保存到本地金标库（embedding + 持久化） */
    val saving: Boolean = false,
    /** 提示消息（用于错误提示等） */
    val errorMessage: String? = null,
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

    // 已加载过的语料库内存缓存：账号来回切换时不再重复读盘 + 解析 JSON。
    // 任何写入 LocalGoldStore 或 corpus sync 后必须 invalidate 对应账号的 entry。
    private val corpusCache = mutableMapOf<BusinessAccount, List<Pair<QAItem, FloatArray>>>()

    // 这几个 service 之前在 loadCorpus / autoSyncCorpus / syncCorpus 里各 new 一份，
    // 提到字段共用一个实例，省构造和重复 mkdirs 检查。
    private val corpusSyncManager = CorpusSyncManager(appContext)
    private val localGoldStore = LocalGoldStore(appContext)
    private val qaCorpusLoader = QACorpusLoader(appContext)

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

    /**
     * 一键把当前账号所有本地金标推到云端。
     * 历史本地金标（v1.0.5 之前加的）从未上云，这个按钮提供一次性回溯。
     * 按 (scene, answer, risk_note) 分组，把 questions 合并后用 FC upsert 协议提交，
     * 重叠的会替换合并 questions，新的就追加。
     */
    fun pushAllLocalGoldToCloud() {
        if (!GoldUploader.isConfigured()) {
            showSnackbar("未配置上传地址")
            return
        }
        val account = _state.value.account
        viewModelScope.launch {
            val all = LocalGoldStore(appContext).load(account)
            if (all.isEmpty()) {
                showSnackbar("本地没有金标，无需回溯")
                return@launch
            }
            // 按 (scene, answer, risk_note) 分组，questions 去重合并
            data class Key(val scene: String, val answer: String, val risk: String)
            val grouped = LinkedHashMap<Key, LinkedHashSet<String>>()
            all.forEach { (item, _) ->
                val q = item.questions.firstOrNull()?.trim().orEmpty()
                if (q.isEmpty()) return@forEach
                val k = Key(item.scene.ifBlank { "其他" }, item.answer, item.riskNote)
                grouped.getOrPut(k) { LinkedHashSet() }.add(q)
            }
            val total = grouped.size
            var ok = 0
            var failed = 0
            grouped.entries.forEachIndexed { idx, (key, qs) ->
                _state.update { it.copy(snackbar = "回溯本地金标到云端：${idx + 1}/$total…") }
                when (GoldUploader.upload(
                    account = account,
                    scene = key.scene,
                    questions = qs.toList(),
                    answer = key.answer,
                    riskNote = key.risk,
                )) {
                    is GoldUploader.Result.Success -> ok++
                    is GoldUploader.Result.Failure -> failed++
                }
            }
            _state.update {
                it.copy(snackbar = "回溯完成：成功 $ok 组，失败 $failed 组（共 $total 组）")
            }
        }
    }

    private suspend fun loadSnippets(account: BusinessAccount) {
        val list = SnippetRepository(appContext).load(account)
        _state.update { it.copy(snippets = list) }
        android.util.Log.d("PanelViewModel", "常用片段加载完成 [${account.key}]: ${list.size} 条")
    }

    // ─── 添加金标 QA 流程 ──────────────────────────────────────────

    fun updateGoldScene(text: String) {
        _state.update { it.copy(addGold = it.addGold.copy(scene = text, errorMessage = null)) }
    }

    fun updateGoldAnswer(text: String) {
        _state.update { it.copy(addGold = it.addGold.copy(answer = text, errorMessage = null)) }
    }

    fun updateGoldRiskNote(text: String) {
        _state.update { it.copy(addGold = it.addGold.copy(riskNote = text)) }
    }

    fun updateGoldDraft(index: Int, text: String) {
        _state.update { st ->
            val list = st.addGold.questionDrafts.toMutableList()
            if (index in list.indices) list[index] = text
            st.copy(addGold = st.addGold.copy(questionDrafts = list))
        }
    }

    fun removeGoldDraft(index: Int) {
        _state.update { st ->
            val list = st.addGold.questionDrafts.toMutableList()
            if (index in list.indices) list.removeAt(index)
            st.copy(addGold = st.addGold.copy(questionDrafts = list))
        }
    }

    fun addGoldDraftBlank() {
        _state.update { st -> st.copy(addGold = st.addGold.copy(questionDrafts = st.addGold.questionDrafts + "")) }
    }

    fun resetGoldForm() {
        _state.update { it.copy(addGold = AddGoldState()) }
    }

    /** 让 LLM 根据当前表单的 scene+answer 反推 Q 变体，写入 questionDrafts。 */
    fun generateGoldQuestionVariants() {
        val current = _state.value.addGold
        if (current.answer.isBlank()) {
            _state.update { it.copy(addGold = it.addGold.copy(errorMessage = "请先填写标准回复")) }
            return
        }
        _state.update { it.copy(addGold = it.addGold.copy(generating = true, errorMessage = null)) }
        viewModelScope.launch {
            val list = aiRepository.generateQuestionVariants(
                scene = current.scene,
                answer = current.answer,
                apiKey = AppConfig.API_KEY,
                baseUrl = AppConfig.API_BASE_URL,
            )
            _state.update { st ->
                st.copy(addGold = st.addGold.copy(
                    generating = false,
                    questionDrafts = list,
                    errorMessage = if (list.isEmpty()) "AI 未返回结果，请检查网络或重试" else null,
                ))
            }
        }
    }

    /** 把当前表单的 (Q1..Qn, A) 写入本地金标库，立即生效到 VectorStore。 */
    fun saveGoldToLocal() {
        val current = _state.value.addGold
        val account = _state.value.account
        val cleanQs = current.questionDrafts.map { it.trim() }.filter { it.isNotEmpty() }
        if (current.answer.isBlank()) {
            _state.update { it.copy(addGold = it.addGold.copy(errorMessage = "请先填写标准回复")) }
            return
        }
        if (cleanQs.isEmpty()) {
            _state.update { it.copy(addGold = it.addGold.copy(errorMessage = "至少需要一个 Q 变体（请生成或手动添加）")) }
            return
        }
        _state.update { it.copy(addGold = it.addGold.copy(saving = true, errorMessage = null)) }
        viewModelScope.launch {
            // 1) 调 embedding API 给每个 Q 取向量
            val vecs = embeddingRepository.embedBatch(cleanQs, AppConfig.API_KEY, AppConfig.API_BASE_URL)
            if (vecs == null || vecs.size != cleanQs.size) {
                _state.update { it.copy(addGold = it.addGold.copy(saving = false, errorMessage = "Embedding 失败，请检查网络")) }
                return@launch
            }
            // 2) 构造条目（每个 Q 一条 QAItem，共享同 answer）
            val sceneFinal = current.scene.ifBlank { "其他" }
            val entries = cleanQs.zip(vecs).map { (q, vec) ->
                com.xingshu.helper.data.model.QAItem(
                    scene = sceneFinal,
                    questions = listOf(q),
                    answer = current.answer,
                    riskNote = current.riskNote,
                    isGold = true,
                ) to vec
            }
            // 3) 持久化 + 立刻 push 到 VectorStore
            localGoldStore.append(account, entries)
            vectorStore.appendEntries(entries)
            invalidateCorpusCache(account)

            // 4) 自动同步到云端（让其他设备也能用）。失败不影响本地保存。
            val cloudMsg = if (GoldUploader.isConfigured()) {
                when (val r = GoldUploader.upload(
                    account = account,
                    scene = sceneFinal,
                    questions = cleanQs,
                    answer = current.answer,
                    riskNote = current.riskNote,
                )) {
                    is GoldUploader.Result.Success -> "，已同步到云端"
                    is GoldUploader.Result.Failure -> "，云端同步失败：${r.message}（仅本地生效）"
                }
            } else ""

            _state.update { st ->
                st.copy(
                    addGold = AddGoldState(),
                    currentScreen = PanelScreen.MAIN,
                    snackbar = "已加入金标库（${cleanQs.size} 条 Q），立即生效$cloudMsg",
                )
            }
        }
    }

    /**
     * 用户在 RAG 结果页编辑某条 QA 的 A，写入本地金标库并立即生效。
     * 复用原向量（question 没变），不调 embedding API。
     */
    fun updateRagAnswer(originalItem: QAItem, newAnswer: String, newRiskNote: String = originalItem.riskNote) {
        val account = _state.value.account
        val question = originalItem.questions.firstOrNull().orEmpty()
        val cleanAnswer = newAnswer.trim()
        if (question.isBlank() || cleanAnswer.isBlank()) {
            showSnackbar("question 或 answer 不能为空")
            return
        }
        val vec = vectorStore.vectorForQuestion(question)
        if (vec == null) {
            showSnackbar("找不到原向量，无法保存")
            return
        }
        val updated = originalItem.copy(
            answer = cleanAnswer,
            riskNote = newRiskNote,
            isGold = true,    // 修订即视为金标
            isLocal = true,
        )
        viewModelScope.launch {
            localGoldStore.upsert(
                account = account,
                question = question,
                scene = updated.scene,
                answer = updated.answer,
                riskNote = updated.riskNote,
                embedding = vec,
            )
            vectorStore.upsertByQuestion(question, updated, vec)
            invalidateCorpusCache(account)
            // 同步更新当前 referencedQas 中匹配的那条，UI 立刻看到新 A
            _state.update { st ->
                st.copy(
                    referencedQas = st.referencedQas.map { ref ->
                        if (ref.item.questions.firstOrNull() == question) ref.copy(item = updated) else ref
                    },
                    snackbar = "已保存修订（立即生效）",
                )
            }
            // 同步上传到云端（FC 按 question 替换；网络失败仅 snackbar 提示，不阻塞）
            if (GoldUploader.isConfigured()) {
                val r = GoldUploader.upload(
                    account = account,
                    scene = updated.scene,
                    questions = listOf(question),
                    answer = updated.answer,
                    riskNote = updated.riskNote,
                )
                val msg = when (r) {
                    is GoldUploader.Result.Success -> "已保存修订（立即生效），已同步到云端"
                    is GoldUploader.Result.Failure -> "已保存修订（立即生效），云端同步失败：${r.message}"
                }
                _state.update { it.copy(snackbar = msg) }
            }
            // 如果当前结果页用的是 GeneratedResult.ragMatches（RAG-only 模式），
            // 也把对应 RagMatch 的 answer 替换掉，UI 立刻看到新版本
            val cur = _state.value.generateState
            if (cur is GenerateState.Success && cur.result.isDirectMatch) {
                val updatedMatches = cur.result.ragMatches.map { rm ->
                    // RagMatch 没有 question 字段——用 scene+answer 匹配旧值（足够区分 top-K）
                    if (rm.scene == originalItem.scene && rm.answer == originalItem.answer) {
                        rm.copy(answer = updated.answer)
                    } else rm
                }
                _state.update { st ->
                    st.copy(generateState = GenerateState.Success(cur.result.copy(ragMatches = updatedMatches)))
                }
            }
        }
    }

    /**
     * 将 RAG 结果中的某条 QA 升级为金标：写入本地金标库（assets 来源条目会被复制一份），
     * 内存里翻转 isGold 标志，UI 立即把这条置顶到第一位并打金标角标。
     * 如果之前被用户取消过金标（在 demoted 列表里），仅清掉 demoted 标记即可。
     */
    fun promoteToGold(item: QAItem) {
        val account = _state.value.account
        val question = item.questions.firstOrNull().orEmpty()
        if (question.isBlank()) {
            showSnackbar("无法识别该条目的 question")
            return
        }
        val vec = vectorStore.vectorForQuestion(question)
        if (vec == null) {
            showSnackbar("找不到原向量，无法升级金标")
            return
        }
        viewModelScope.launch {
            val store = localGoldStore
            val demoted = store.loadDemoted(account)
            if (question in demoted) {
                // 之前被降级过，撤销即可恢复金标 boost
                store.removeDemoted(account, question)
            } else if (!item.isLocal) {
                // assets 非金标条目，复制一份到本地金标 store
                store.promote(
                    account = account,
                    question = question,
                    scene = item.scene,
                    answer = item.answer,
                    riskNote = item.riskNote,
                    embedding = vec,
                )
            }
            vectorStore.setGoldByQuestion(question, true)
            invalidateCorpusCache(account)
            applyGoldFlagToState(question, true)
            showSnackbar("已设为金标，已置顶并下次检索继续生效")
        }
    }

    /**
     * 将某条 QA 取消金标：写入 demoted 列表（同时清掉本地金标 store 里的对应行），
     * VectorStore 内存中 isGold 翻 false，UI 移除该条的角标和置顶。
     */
    fun demoteFromGold(item: QAItem) {
        val account = _state.value.account
        val question = item.questions.firstOrNull().orEmpty()
        if (question.isBlank()) {
            showSnackbar("无法识别该条目的 question")
            return
        }
        viewModelScope.launch {
            localGoldStore.addDemoted(account, question)
            vectorStore.setGoldByQuestion(question, false)
            invalidateCorpusCache(account)
            applyGoldFlagToState(question, false)
            showSnackbar("已取消金标")
        }
    }

    /**
     * 翻转 state 中 question 对应条目的 isGold 标志：
     * - RAG-only 结果页（ragMatches 与 referencedQas 索引对齐）：同步两边、稳定排序金标置顶。
     * - 其它情况：只更新 referencedQas，避免动 ragMatches 索引。
     */
    private fun applyGoldFlagToState(question: String, isGold: Boolean) {
        _state.update { st ->
            val cur = st.generateState
            if (cur is GenerateState.Success
                && cur.result.isDirectMatch
                && st.referencedQas.size == cur.result.ragMatches.size
            ) {
                val zipped = cur.result.ragMatches.zip(st.referencedQas).map { (rm, ref) ->
                    val q = ref.item.questions.firstOrNull()
                    if (q == question) {
                        rm.copy(isGold = isGold) to ref.copy(item = ref.item.copy(isGold = isGold))
                    } else rm to ref
                }
                val sorted = zipped.withIndex().sortedWith(
                    compareByDescending<IndexedValue<Pair<RagMatch, ReferencedQa>>> { it.value.first.isGold }
                        .thenBy { it.index }
                ).map { it.value }
                st.copy(
                    generateState = GenerateState.Success(
                        cur.result.copy(ragMatches = sorted.map { it.first })
                    ),
                    referencedQas = sorted.map { it.second },
                )
            } else {
                val newRefs = st.referencedQas.map { ref ->
                    if (ref.item.questions.firstOrNull() == question) {
                        ref.copy(item = ref.item.copy(isGold = isGold))
                    } else ref
                }
                st.copy(referencedQas = newRefs)
            }
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

    /** 默认入口：直接 RAG 匹配，不调 LLM。结果页上若用户不满意可点"结合 AI"按钮再走 generateWithAi()。 */
    private fun doGenerate(messages: List<String>) {
        _state.update { it.copy(lastQuery = LastQuery.Messages(messages)) }
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            doRagOnly(messages.joinToString("\n"))
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
            doRagOnly(customerOnly)
        }
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
            RagMatch(scene = item.scene, answer = item.answer, score = score, isGold = item.isGold)
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
        // 稳定排序：金标条目永远排在最前，非金标按原 boosted 顺序保留
        val sorted = hits.sortedByDescending { (item, _) -> item.isGold }
        // 把检索结果（带分数）写入 state，RAG+AI 模式下结果页展示「参考话术」面板
        _state.update { it.copy(referencedQas = sorted.map { (item, score) -> ReferencedQa(item, score) }) }
        return sorted
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
