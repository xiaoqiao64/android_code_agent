package com.xiaoqiao.codeagent.ui.files

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoqiao.codeagent.runtime.Bootstrap
import com.xiaoqiao.codeagent.runtime.FsEntry
import com.xiaoqiao.codeagent.runtime.WorkspaceFs
import com.xiaoqiao.codeagent.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FileBrowserState(
    val workspace: String? = null,
    val relative: String = ".",
    val entries: List<FsEntry> = emptyList(),
    val title: String = "Files",
    val canGoUp: Boolean = false,
    val error: String? = null,
    val ready: Boolean = false,
    val viewing: Viewing? = null,
)

class FileBrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SessionStore(app)
    private val _state = MutableStateFlow(FileBrowserState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private fun rootOrNull(): File? {
        val ws = _state.value.workspace ?: return null
        val root = WorkspaceFs.hostRoot(Bootstrap.debianDir(getApplication()), ws).canonicalFile
        root.mkdirs()
        return root
    }

    private suspend fun load() {
        val idx = store.list()
        val session = idx.activeId?.let { store.load(it) }
            ?: idx.sessions.firstOrNull()?.let { store.load(it.id) }
        _state.update { it.copy(workspace = session?.workspace, relative = ".", ready = true) }
        refresh()
    }

    fun refresh() {
        val root = rootOrNull() ?: run {
            _state.update { it.copy(entries = emptyList(), title = "No workspace", canGoUp = false) }
            return
        }
        val rel = _state.value.relative
        val entries = runCatching { WorkspaceFs.list(root, rel) }.getOrElse { emptyList() }
        val title = if (rel == "." || rel.isBlank()) {
            _state.value.workspace?.substringAfterLast('/') ?: "Files"
        } else {
            rel
        }
        _state.update {
            it.copy(
                entries = entries,
                title = title,
                canGoUp = WorkspaceFs.parentRelative(rel) != null,
                error = null,
            )
        }
    }

    fun enter(entry: FsEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(relative = entry.relativePath) }
        refresh()
    }

    fun goUp() {
        val parent = WorkspaceFs.parentRelative(_state.value.relative) ?: return
        _state.update { it.copy(relative = parent) }
        refresh()
    }

    fun openInternal(entry: FsEntry) {
        val root = rootOrNull() ?: return
        val kind = FileKinds.of(entry.name)
        if (!kind.internal) return
        runCatching {
            val file = WorkspaceFs.resolve(root, entry.relativePath)
            _state.update { it.copy(viewing = Viewing(entry, file, kind), error = null) }
        }.onFailure { err ->
            _state.update { it.copy(error = err.message ?: "open failed") }
        }
    }

    fun closeViewer() {
        _state.update { it.copy(viewing = null) }
    }

    fun openExternal(entry: FsEntry): Intent? {
        val ctx = getApplication<Application>()
        val root = rootOrNull() ?: return null
        return runCatching {
            val src = WorkspaceFs.resolve(root, entry.relativePath)
            val uri = FileProvider.getUriForFile(ctx, FILE_PROVIDER, src)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, FileKinds.mimeOf(entry.name))
                clipData = ClipData.newRawUri(entry.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.onFailure { err ->
            _state.update { it.copy(error = err.message ?: "open failed") }
        }.getOrNull()
    }

    fun rename(entry: FsEntry, newName: String) {
        viewModelScope.launch {
            val root = rootOrNull() ?: return@launch
            val result = runCatching {
                withContext(Dispatchers.IO) { WorkspaceFs.rename(root, entry.relativePath, newName) }
            }
            refresh()
            result.onFailure { err ->
                _state.update { it.copy(error = err.message ?: "rename failed") }
            }
        }
    }

    fun delete(entry: FsEntry) {
        viewModelScope.launch {
            val root = rootOrNull() ?: return@launch
            val result = runCatching {
                withContext(Dispatchers.IO) { WorkspaceFs.delete(root, entry.relativePath) }
            }
            refresh()
            result.onFailure { err ->
                _state.update { it.copy(error = err.message ?: "delete failed") }
            }
        }
    }

    suspend fun saveTo(entry: FsEntry, uri: Uri): Boolean {
        val ctx = getApplication<Application>()
        val root = rootOrNull() ?: return false
        return runCatching {
            withContext(Dispatchers.IO) {
                val src = WorkspaceFs.resolve(root, entry.relativePath)
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    WorkspaceFs.copyToStream(src, out)
                } ?: error("cannot open destination")
            }
        }.onFailure { err ->
            _state.update { it.copy(error = err.message ?: "save failed") }
        }.isSuccess
    }

    suspend fun shareFile(entry: FsEntry): Intent? {
        val ctx = getApplication<Application>()
        val root = rootOrNull() ?: return null
        return runCatching {
            withContext(Dispatchers.IO) {
                val src = WorkspaceFs.resolve(root, entry.relativePath)
                val shareDir = File(ctx.cacheDir, "share").also { it.mkdirs() }
                val dest = File(shareDir, entry.name)
                src.copyTo(dest, overwrite = true)
                val uri = FileProvider.getUriForFile(ctx, FILE_PROVIDER, dest)
                Intent(Intent.ACTION_SEND).apply {
                    type = FileKinds.mimeOf(entry.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(entry.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }.onFailure { err ->
            _state.update { it.copy(error = err.message ?: "share failed") }
        }.getOrNull()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(onBack: () -> Unit, vm: FileBrowserViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val viewing = state.viewing
    if (viewing != null) {
        FileViewerScreen(viewing, onBack = vm::closeViewer)
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<FsEntry?>(null) }
    var renameFor by remember { mutableStateOf<FsEntry?>(null) }
    var deleteFor by remember { mutableStateOf<FsEntry?>(null) }
    var pendingSave by remember { mutableStateOf<FsEntry?>(null) }
    var renameText by remember { mutableStateOf("") }

    fun launchExternal(entry: FsEntry) {
        val intent = vm.openExternal(entry) ?: return
        try {
            context.startActivity(Intent.createChooser(intent, "用其他应用打开"))
        } catch (_: Exception) {
            Toast.makeText(context, "没有可打开的应用", Toast.LENGTH_SHORT).show()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = pendingSave
        pendingSave = null
        if (uri != null && entry != null) {
            scope.launch {
                val ok = vm.saveTo(entry, uri)
                if (ok) Toast.makeText(context, "已保存 ${entry.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::goUp, enabled = state.canGoUp) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                !state.ready -> {}
                state.workspace == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有当前会话工作目录")
                    }
                }
                state.entries.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("空目录")
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.entries, key = { it.relativePath }) { entry ->
                            Box {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                when {
                                                    entry.isDirectory -> vm.enter(entry)
                                                    FileKinds.of(entry.name).internal -> vm.openInternal(entry)
                                                    else -> launchExternal(entry)
                                                }
                                            },
                                            onLongClick = { menuFor = entry },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(
                                    expanded = menuFor == entry,
                                    onDismissRequest = { menuFor = null },
                                ) {
                                    if (!entry.isDirectory) {
                                        if (FileKinds.of(entry.name).internal) {
                                            DropdownMenuItem(
                                                text = { Text("打开") },
                                                onClick = {
                                                    menuFor = null
                                                    vm.openInternal(entry)
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("其他应用打开") },
                                            onClick = {
                                                menuFor = null
                                                launchExternal(entry)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("保存") },
                                            onClick = {
                                                menuFor = null
                                                pendingSave = entry
                                                saveLauncher.launch(entry.name)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("分享") },
                                            onClick = {
                                                menuFor = null
                                                scope.launch {
                                                    val intent = vm.shareFile(entry) ?: return@launch
                                                    try {
                                                        context.startActivity(Intent.createChooser(intent, "分享"))
                                                    } catch (_: Exception) {
                                                        Toast.makeText(context, "没有可分享的应用", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("改名") },
                                        onClick = {
                                            menuFor = null
                                            renameText = entry.name
                                            renameFor = entry
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        onClick = {
                                            menuFor = null
                                            deleteFor = entry
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val renaming = renameFor
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("改名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("新名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(renaming, renameText)
                    renameFor = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameFor = null }) { Text("取消") }
            },
        )
    }

    val deleting = deleteFor
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除") },
            text = { Text("确定删除「${deleting.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(deleting)
                    deleteFor = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text("取消") }
            },
        )
    }
}
