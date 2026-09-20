package com.xiaoqiao.codeagent.ui.shell

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaoqiao.codeagent.runtime.Bootstrap
import com.xiaoqiao.codeagent.runtime.Proot
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private const val TAG = "TerminalScreen"
private const val FONT_SIZE_SP = 18

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val holder = remember { TerminalHolder(context) }

    DisposableEffect(Unit) {
        val window = (context as? ComponentActivity)?.window
        val previousMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        holder.start()
        holder.requestKeyboard()
        onDispose {
            if (previousMode != null) {
                window?.setSoftInputMode(previousMode)
            }
            holder.destroy()
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
            override fun onEmulatorSet() {
                requestKeyboard()
            }
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
                override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
                override fun onPasteTextFromClipboard(session: TerminalSession?) {}
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
            requestKeyboard()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start terminal", e)
        }
    }

    fun requestKeyboard() {
        val v = view ?: return
        v.post {
            v.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(v, InputMethodManager.SHOW_FORCED)
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
