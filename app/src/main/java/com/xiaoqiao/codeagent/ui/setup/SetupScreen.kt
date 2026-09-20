package com.xiaoqiao.codeagent.ui.setup

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoqiao.codeagent.agent.Agents
import com.xiaoqiao.codeagent.runtime.Bootstrap
import com.xiaoqiao.codeagent.runtime.Proot
import com.xiaoqiao.codeagent.runtime.RootfsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class SetupUiState(
    val running: Boolean = false,
    val progress: Int = 0,
    val stage: String = "Ready",
    val log: String = "",
    val selectedAgents: Set<String> = setOf("claude"),
    val done: Boolean = false,
    val error: String? = null,
)

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(SetupUiState())
    val state = _state.asStateFlow()

    fun toggleAgent(id: String) {
        _state.update { s ->
            val next = s.selectedAgents.toMutableSet()
            if (id in next) next.remove(id) else next.add(id)
            s.copy(selectedAgents = next)
        }
    }

    fun start() {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null, log = "", progress = 0) }
            try {
                val ctx = getApplication<Application>()
                append("Bootstrapping native payloads…")
                withContext(Dispatchers.IO) { Bootstrap.ensureBootstrap(ctx) }

                if (!RootfsInstaller.isInstalled(ctx)) {
                    RootfsInstaller.install(ctx).collect { p ->
                        _state.update {
                            it.copy(progress = p.percent, stage = p.stage, log = it.log + p.detail + "\n")
                        }
                    }
                } else {
                    append("Debian rootfs already installed.")
                    _state.update { it.copy(progress = 50, stage = "rootfs") }
                }

                val selected = _state.value.selectedAgents
                val installLines = selected.mapNotNull { id -> Agents.byId(id).installCmd }
                val script = buildSetupScript(installLines)
                append("Writing DNS resolv.conf for proot…")
                withContext(Dispatchers.IO) { RootfsInstaller.ensureGuestNetwork(ctx) }

                append("Running provisioning inside Debian…")
                _state.update { it.copy(stage = "provision", progress = 60) }

                withContext(Dispatchers.IO) {
                    val scriptFile = java.io.File(Bootstrap.debianDir(ctx), "root/setup_agents.sh")
                    scriptFile.parentFile?.mkdirs()
                    scriptFile.writeText(script)
                    scriptFile.setExecutable(true)

                    val pb = Proot.processBuilder(
                        ctx,
                        listOf("bash", "/root/setup_agents.sh"),
                        cwd = "/root",
                    )
                    val proc = pb.start()
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            append(line)
                        }
                    }
                    val code = proc.waitFor()
                    if (code != 0) throw IllegalStateException("setup exited $code")
                }

                Bootstrap.agentsInstalledMarker(ctx).writeText(selected.joinToString(","))
                Bootstrap.workspaceHostDir(ctx).mkdirs()
                _state.update { it.copy(running = false, done = true, progress = 100, stage = "done") }
                append("Setup complete. Log in to agents from the Shell screen.")
            } catch (e: Exception) {
                val detail = e.stackTraceToString()
                _state.update {
                    it.copy(running = false, error = e.message ?: e.toString(), stage = "error")
                }
                append("ERROR: $detail")
            }
        }
    }

    private fun append(line: String) {
        _state.update { it.copy(log = it.log + line + "\n") }
    }

    private fun buildSetupScript(installCmds: List<String>): String = buildString {
        appendLine("#!/bin/bash")
        appendLine("set -e")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        appendLine("apt-get update")
        appendLine("apt-get install -y curl ca-certificates git nodejs npm")
        for (cmd in installCmds) {
            appendLine("echo \"==> $cmd\"")
            appendLine(cmd)
        }
        appendLine("mkdir -p /root/workspace")
        appendLine("echo SETUP_OK")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupScreen(
    onReady: () -> Unit,
    onOpenShell: () -> Unit,
    vm: SetupViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "$label 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    val errorCopyText = buildString {
        if (!state.error.isNullOrBlank()) {
            appendLine(state.error)
            appendLine()
        }
        append(state.log)
    }.trim()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("First-time setup", style = MaterialTheme.typography.headlineSmall)
            Text("Downloads a Debian rootfs via proot, then installs the selected code agent CLIs.")
            Text("Select agents to install:", style = MaterialTheme.typography.titleMedium)
            Agents.all.forEach { agent ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = agent.id in state.selectedAgents,
                        onCheckedChange = { vm.toggleAgent(agent.id) },
                        enabled = !state.running,
                    )
                    Text(agent.label)
                }
            }
            if (state.running || state.progress > 0) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${state.stage} · ${state.progress}%")
            }
            if (state.error != null) {
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { copyToClipboard("错误信息", errorCopyText) },
                        ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.start() }, enabled = !state.running && state.selectedAgents.isNotEmpty()) {
                    Text(if (state.running) "Working…" else "Start setup")
                }
                OutlinedButton(onClick = onOpenShell) {
                    Text("Open shell")
                }
                if (state.done) {
                    Button(onClick = onReady) { Text("Continue") }
                }
            }
            Text(
                text = state.log,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { copyToClipboard("错误信息", errorCopyText.ifBlank { state.log }) },
                    ),
            )
        }
    }
}
