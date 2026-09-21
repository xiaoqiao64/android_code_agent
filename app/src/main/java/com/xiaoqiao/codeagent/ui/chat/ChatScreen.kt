package com.xiaoqiao.codeagent.ui.chat

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoqiao.codeagent.agent.AgentEvent
import com.xiaoqiao.codeagent.agent.AgentRunConfig
import com.xiaoqiao.codeagent.agent.ModelCatalog
import com.xiaoqiao.codeagent.agent.ModelLister
import com.xiaoqiao.codeagent.agent.AgentForegroundService
import com.xiaoqiao.codeagent.agent.AgentRunner
import com.xiaoqiao.codeagent.agent.Agents
import com.xiaoqiao.codeagent.runtime.Bootstrap
import com.xiaoqiao.codeagent.session.AgentSession
import com.xiaoqiao.codeagent.session.ChatMessage
import com.xiaoqiao.codeagent.session.SessionStore
import com.xiaoqiao.codeagent.session.SessionSummary
import com.xiaoqiao.codeagent.ui.ssh.SshdButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class ChatUiState(
    val session: AgentSession? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val input: String = "",
    val running: Boolean = false,
    val streaming: String = "",
    val fileSuggestions: List<String> = emptyList(),
    val error: String? = null,
    val catalog: ModelCatalog = ModelCatalog.EMPTY,
    val catalogLoading: Boolean = false,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val runner = AgentRunner(app)
    private val lister = ModelLister(app)
    private val catalogCache = mutableMapOf<String, ModelCatalog>()
    private val _state = MutableStateFlow(ChatUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            reload()
            _state.value.session?.agentId?.let { refreshCatalog(it) }
        }
    }

    suspend fun reload() {
        val idx = store.list()
        val active = idx.activeId?.let { store.load(it) } ?: idx.sessions.firstOrNull()?.let { store.load(it.id) }
        _state.update { it.copy(session = active, sessions = idx.sessions) }
    }

    fun setInput(text: String) {
        _state.update { s ->
            val suggestions = if (text.contains('@')) {
                val partial = text.substringAfterLast('@')
                listWorkspaceFiles(s.session?.workspace).filter {
                    partial.isBlank() || it.contains(partial, ignoreCase = true)
                }.take(12)
            } else emptyList()
            s.copy(input = text, fileSuggestions = suggestions)
        }
    }

    fun acceptSuggestion(path: String) {
        _state.update { s ->
            val before = s.input.substringBeforeLast('@')
            s.copy(input = before + "@$path ", fileSuggestions = emptyList())
        }
    }

    fun switchAgent(agentId: String) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            val updated = session.copy(
                agentId = agentId,
                modelId = null,
                effort = null,
                thinking = false,
                fast = false,
                updatedAt = System.currentTimeMillis(),
            )
            store.save(updated)
            _state.update { it.copy(session = updated) }
            refreshCatalog(agentId)
        }
    }

    fun switchModel(modelId: String) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            val updated = session.copy(
                modelId = modelId,
                effort = null,
                thinking = false,
                fast = false,
                updatedAt = System.currentTimeMillis(),
            )
            store.save(updated)
            _state.update { it.copy(session = updated) }
        }
    }

    fun switchEffort(effort: String?) {
        patchSession { it.copy(effort = effort?.ifBlank { null }) }
    }

    fun switchThinking(thinking: Boolean) {
        patchSession { it.copy(thinking = thinking) }
    }

    fun switchFast(fast: Boolean) {
        patchSession { it.copy(fast = fast) }
    }

    fun refreshCatalog(agentId: String) {
        viewModelScope.launch {
            catalogCache[agentId]?.let { cached ->
                _state.update { it.copy(catalog = cached, catalogLoading = false) }
            } ?: _state.update { it.copy(catalogLoading = true) }
            val cat = runCatching { lister.list(Agents.byId(agentId)) }.getOrDefault(ModelCatalog.EMPTY)
            catalogCache[agentId] = cat
            _state.update { it.copy(catalog = cat, catalogLoading = false) }
        }
    }

    private fun patchSession(block: (AgentSession) -> AgentSession) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            val updated = block(session).copy(updatedAt = System.currentTimeMillis())
            store.save(updated)
            _state.update { it.copy(session = updated) }
        }
    }

    fun openSession(id: String) {
        viewModelScope.launch {
            val s = store.load(id)
            if (s != null) {
                store.setActive(id)
                _state.update { it.copy(session = s) }
                refreshCatalog(s.agentId)
            }
        }
    }

    fun importFile(uri: Uri, name: String) {
        val session = _state.value.session ?: return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val hostWs = File(Bootstrap.debianDir(ctx), session.workspace.removePrefix("/"))
            hostWs.mkdirs()
            val dest = File(hostWs, name)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            setInput(_state.value.input + (if (_state.value.input.isBlank()) "" else " ") + "@$name ")
        }
    }

    fun send() {
        val session = _state.value.session ?: return
        val prompt = _state.value.input.trim()
        if (prompt.isEmpty() || _state.value.running) return

        viewModelScope.launch {
            val userMsg = ChatMessage(UUID.randomUUID().toString(), "user", prompt)
            var current = session.copy(
                messages = session.messages + userMsg,
                updatedAt = System.currentTimeMillis(),
                title = if (session.messages.isEmpty()) prompt.take(40) else session.title,
            )
            store.save(current)
            _state.update { it.copy(session = current, input = "", running = true, streaming = "", error = null, fileSuggestions = emptyList()) }

            val ctx = getApplication<Application>()
            AgentForegroundService.start(ctx)
            val agent = Agents.byId(current.agentId)
            val cfg = runConfig(current, _state.value.catalog)
            val assistantBuf = StringBuilder()
            var resumeId = current.resumeId
            try {
                runner.run(agent, prompt, current.workspace, resumeId, cfg).collect { ev ->
                    when (ev) {
                        is AgentEvent.TextDelta -> {
                            assistantBuf.append(ev.text)
                            _state.update { it.copy(streaming = assistantBuf.toString()) }
                        }
                        is AgentEvent.AssistantMessage -> {
                            if (assistantBuf.isEmpty()) {
                                assistantBuf.append(ev.text)
                                _state.update { it.copy(streaming = assistantBuf.toString()) }
                            }
                        }
                        is AgentEvent.ToolCall -> {
                            if (assistantBuf.isNotEmpty()) {
                                val aMsg = ChatMessage(
                                    UUID.randomUUID().toString(),
                                    "assistant",
                                    assistantBuf.toString(),
                                )
                                current = current.copy(messages = current.messages + aMsg)
                                assistantBuf.clear()
                            }
                            val toolMsg = ChatMessage(
                                UUID.randomUUID().toString(),
                                "tool",
                                ev.detail,
                                toolName = ev.name,
                            )
                            current = current.copy(messages = current.messages + toolMsg)
                            _state.update { it.copy(session = current, streaming = "") }
                        }
                        is AgentEvent.SessionId -> resumeId = ev.id
                        is AgentEvent.Error -> _state.update { it.copy(error = ev.message) }
                        is AgentEvent.Log -> { /* optional */ }
                        is AgentEvent.Finished -> { /* handled after collect */ }
                    }
                }
            } finally {
                AgentForegroundService.stop(ctx)
            }
            val assistantText = assistantBuf.toString()
            if (assistantText.isNotBlank()) {
                val aMsg = ChatMessage(UUID.randomUUID().toString(), "assistant", assistantText)
                current = current.copy(messages = current.messages + aMsg)
            }
            current = current.copy(resumeId = resumeId, updatedAt = System.currentTimeMillis())
            store.save(current)
            val idx = store.list()
            _state.update {
                it.copy(session = current, sessions = idx.sessions, running = false, streaming = "")
            }
        }
    }

    private fun listWorkspaceFiles(workspace: String?): List<String> {
        if (workspace == null) return emptyList()
        val ctx = getApplication<Application>()
        val host = File(Bootstrap.debianDir(ctx), workspace.removePrefix("/"))
        if (!host.isDirectory) return emptyList()
        return host.walkTopDown().maxDepth(3)
            .filter { it.isFile }
            .map { it.relativeTo(host).path }
            .toList()
    }

    private fun runConfig(session: AgentSession, catalog: ModelCatalog): AgentRunConfig {
        val selected = catalog.find(session.modelId)
        val slug = when {
            selected == null -> session.modelId
            selected.id.isEmpty() -> null
            selected.effortViaFlag -> selected.id
            else -> selected.resolve(session.effort, session.thinking, session.fast)
        }
        return AgentRunConfig(
            model = slug,
            effort = if (selected?.effortViaFlag == true) session.effort else null,
            thinking = session.thinking,
            fast = session.fast,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenShell: () -> Unit,
    onNewSession: () -> Unit,
    vm: ChatViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var agentMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selectedModel = state.catalog.find(state.session?.modelId)
    val effortOptions = when {
        selectedModel?.effortViaFlag == true -> state.catalog.flagEffortLevels
        else -> selectedModel?.efforts.orEmpty()
    }
    val showEffort = selectedModel != null && selectedModel.id.isNotEmpty() &&
        (selectedModel.showEffort || (selectedModel.effortViaFlag && effortOptions.isNotEmpty()))
    val showThinking = selectedModel?.supportsThinking == true
    val showFast = selectedModel?.supportsFast == true

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "import.bin"
            vm.importFile(uri, name)
        }
    }

    LaunchedEffect(state.session?.messages?.size, state.streaming) {
        val count = (state.session?.messages?.size ?: 0) + if (state.streaming.isNotEmpty()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    // Right-side drawer via RTL layout direction trick
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            ),
            drawerState = drawerState,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SessionDrawer(
                        sessions = state.sessions,
                        activeId = state.session?.id,
                        onSelect = {
                            vm.openSession(it)
                            scope.launch { drawerState.close() }
                        },
                        onNew = {
                            scope.launch { drawerState.close() }
                            onNewSession()
                        },
                    )
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                    ),
                    topBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(
                                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                    ),
                                )
                                .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    state.session?.title ?: "Code Agent",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    state.session?.workspace ?: "No workspace",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box {
                                    TextButton(onClick = { agentMenu = true }) {
                                        Text(Agents.byId(state.session?.agentId ?: "claude").label)
                                    }
                                    DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                                        Agents.all.forEach { agent ->
                                            DropdownMenuItem(
                                                text = { Text(agent.label) },
                                                onClick = {
                                                    agentMenu = false
                                                    vm.switchAgent(agent.id)
                                                },
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                SshdButton()
                                IconButton(onClick = onOpenShell) {
                                    Icon(Icons.Default.Terminal, contentDescription = "Shell")
                                }
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.History, contentDescription = "Sessions")
                                }
                            }
                            HorizontalDivider()
                        }
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                            ),
                    ) {
                        if (state.session == null) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("No session yet")
                                    TextButton(onClick = onNewSession) { Text("Create session") }
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(state.session!!.messages, key = { it.id }) { msg ->
                                    MessageBubble(msg)
                                }
                                if (state.streaming.isNotEmpty()) {
                                    item {
                                        MessageBubble(
                                            ChatMessage("streaming", "assistant", state.streaming),
                                        )
                                    }
                                } else if (state.running) {
                                    item {
                                        MessageBubble(
                                            ChatMessage("streaming", "assistant", "正在生成…"),
                                        )
                                    }
                                }
                            }
                        }
                        if (state.error != null) {
                            Text(
                                state.error!!,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                        if (state.fileSuggestions.isNotEmpty()) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                                state.fileSuggestions.forEach { path ->
                                    Text(
                                        path,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { vm.acceptSuggestion(path) }
                                            .padding(4.dp),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box {
                                TextButton(
                                    onClick = { modelMenu = true },
                                    enabled = state.session != null && !state.running,
                                ) {
                                    Text(
                                        when {
                                            state.catalogLoading && state.catalog.models.size <= 1 -> "加载模型…"
                                            else -> state.catalog.label(state.session?.modelId)
                                        },
                                    )
                                }
                                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                    state.catalog.models.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model.label) },
                                            onClick = {
                                                modelMenu = false
                                                vm.switchModel(model.id)
                                            },
                                        )
                                    }
                                }
                            }
                            if (showEffort) {
                                Box {
                                    TextButton(
                                        onClick = { effortMenu = true },
                                        enabled = !state.running,
                                    ) {
                                        Text(state.session?.effort?.ifBlank { null } ?: "Effort")
                                    }
                                    DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Default") },
                                            onClick = {
                                                effortMenu = false
                                                vm.switchEffort(null)
                                            },
                                        )
                                        effortOptions.forEach { level ->
                                            DropdownMenuItem(
                                                text = { Text(level) },
                                                onClick = {
                                                    effortMenu = false
                                                    vm.switchEffort(level)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            if (showThinking) {
                                TextButton(
                                    onClick = { vm.switchThinking(!(state.session?.thinking ?: false)) },
                                    enabled = !state.running,
                                ) {
                                    Text(if (state.session?.thinking == true) "Thinking ✓" else "Thinking")
                                }
                            }
                            if (showFast) {
                                Text("Fast", style = MaterialTheme.typography.labelLarge)
                                Switch(
                                    checked = state.session?.fast == true,
                                    onCheckedChange = { vm.switchFast(it) },
                                    enabled = !state.running,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.Add, contentDescription = "Import file")
                            }
                            OutlinedTextField(
                                value = state.input,
                                onValueChange = vm::setInput,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Message… (@file to mention)") },
                                enabled = state.session != null && !state.running,
                            )
                            IconButton(
                                onClick = { vm.send() },
                                enabled = state.session != null && !state.running && state.input.isNotBlank(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val bg = when (msg.role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer
        "tool" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .background(bg, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            if (msg.role == "tool") {
                Text("🔧 ${msg.toolName ?: "tool"}", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                msg.content,
                fontFamily = if (msg.role == "tool") FontFamily.Monospace else FontFamily.Default,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SessionDrawer(
    sessions: List<SessionSummary>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.85f)
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Text("Sessions", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onNew, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("+ New session")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sessions, key = { it.id }) { s ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (s.id == activeId) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onSelect(s.id) }
                        .padding(10.dp),
                ) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall)
                    Text("${Agents.byId(s.agentId).label} · ${s.workspace}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
