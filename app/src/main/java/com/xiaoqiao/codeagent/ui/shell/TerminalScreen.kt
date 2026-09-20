package com.xiaoqiao.codeagent.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.xiaoqiao.codeagent.runtime.Bootstrap
import com.xiaoqiao.codeagent.runtime.Proot
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private const val TAG = "TerminalScreen"
private const val FONT_SIZE_SP = 18

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val holder = remember { TerminalHolder(context) }
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        holder.start()
        holder.requestKeyboard()
        onDispose { holder.destroy() }
    }

    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            delay(250)
            // User dismissed the IME: drop focus so a layout/resize does not pull it back.
            holder.releaseFocus()
            imeWasVisible = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Debian Shell") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { holder.createView() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF222222))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            ) {
                ExtraKey("ESC") { holder.write("\u001b") }
                ExtraKey("CTRL") { holder.toggleCtrl() }
                ExtraKey("TAB") { holder.write("\t") }
                ExtraKey("PASTE") { holder.pasteClipboard() }
                ExtraKey("↑") { holder.write("\u001b[A") }
                ExtraKey("↓") { holder.write("\u001b[B") }
                ExtraKey("←") { holder.write("\u001b[D") }
                ExtraKey("→") { holder.write("\u001b[C") }
            }
        }
    }
}

@Composable
private fun ExtraKey(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

private class TerminalHolder(private val context: Context) {
    private var session: TerminalSession? = null
    private var view: TerminalView? = null
    private var ctrlDown = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun createView(): TerminalView {
        val fontPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            FONT_SIZE_SP.toFloat(),
            context.resources.displayMetrics,
        ).toInt().coerceAtLeast(18)
        val v = TerminalView(context, null).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setTextSize(fontPx)
        }
        view = v
        session?.let { v.attachSession(it) }
        v.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale
            override fun onSingleTapUp(e: android.view.MotionEvent) {
                requestKeyboard()
            }
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
            override fun onLongPress(event: android.view.MotionEvent): Boolean = false
            override fun readControlKey(): Boolean = ctrlDown.also { if (it) ctrlDown = false }
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
            override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
            override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
            override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
            override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
                Log.e(tag ?: TAG, message, e)
            }
            override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }
        })
        return v
    }

    fun start() {
        try {
            Bootstrap.ensureBootstrap(context)
            if (!Bootstrap.debianDir(context).exists()) {
                Log.e(TAG, "Debian rootfs missing")
                return
            }
            val cmd = Proot.command(context, listOf("/bin/bash", "--login"), cwd = "/root")
            val shellPath = cmd.argv.first()
            val args = cmd.argv.drop(1).toTypedArray()
            val env = cmd.env.map { "${it.key}=${it.value}" }.toTypedArray()
            val client = object : TerminalSessionClient {
                override fun onTextChanged(changedSession: TerminalSession) {
                    view?.onScreenUpdated()
                }
                override fun onTitleChanged(changedSession: TerminalSession) {}
                override fun onSessionFinished(finishedSession: TerminalSession) {}
                override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
                    copyToClipboard(text)
                }
                override fun onPasteTextFromClipboard(session: TerminalSession?) {
                    pasteClipboard()
                }
                override fun onBell(session: TerminalSession) {}
                override fun onColorsChanged(session: TerminalSession) {}
                override fun onTerminalCursorStateChange(state: Boolean) {}
                override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
                override fun getTerminalCursorStyle(): Int = 0
                override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
                override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
                override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
                override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
                override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
                override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
                    Log.e(tag ?: TAG, message, e)
                }
                override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }
            }
            val sess = TerminalSession(shellPath, cmd.workingDir.absolutePath, args, env, 2000, client)
            session = sess
            view?.attachSession(sess)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start terminal", e)
        }
    }

    fun requestKeyboard() {
        val v = view ?: return
        v.post {
            v.requestFocus()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = v.windowInsetsController
                if (controller != null) {
                    controller.show(android.view.WindowInsets.Type.ime())
                    return@post
                }
            }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(v, 0)
        }
    }

    fun releaseFocus() {
        view?.clearFocus()
    }

    fun copyToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        runOnMain {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    fun pasteClipboard() {
        runOnMain {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) {
                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                return@runOnMain
            }
            val text = clip.getItemAt(0).coerceToText(context).toString()
            if (text.isEmpty()) {
                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                return@runOnMain
            }
            val emulator = session?.emulator
            if (emulator != null) {
                emulator.paste(text)
            } else {
                session?.write(text)
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun write(data: String) {
        session?.write(data)
    }

    fun toggleCtrl() {
        ctrlDown = !ctrlDown
    }

    fun destroy() {
        session?.finishIfRunning()
        session = null
        view = null
    }
}
