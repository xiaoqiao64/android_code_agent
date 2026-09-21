package com.xiaoqiao.codeagent.ui.ssh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xiaoqiao.codeagent.runtime.SshdInfo
import com.xiaoqiao.codeagent.runtime.SshdServer

@Composable
fun SshdButton() {
    var open by remember { mutableStateOf(false) }
    val state by SshdServer.state.collectAsState()
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.Default.VpnKey,
            contentDescription = "SSH",
            tint = if (state.running) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
    if (open) {
        SshdDialog(onDismiss = { open = false })
    }
}

@Composable
fun SshdDialog(onDismiss: () -> Unit) {
    val state by SshdServer.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val info = state.info

    fun copyAll() {
        val text = info?.copyText() ?: return
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "SSH 连接信息已复制", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.running) "SSH 已开启" else "SSH") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    state.starting -> {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("正在安装/启动 sshd…")
                        if (state.log.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.log.takeLast(1500),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    info != null && state.running -> {
                        Text("点击下方命令复制用户名、密码、端口和 IP")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            info.copyText(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { copyAll() },
                        )
                    }
                    else -> {
                        Text("在 Debian 环境中启动 sshd（端口 ${SshdInfo.PORT}）。未安装 openssh-server 时会自动安装。应用会以前台服务保持后台运行。")
                        if (!state.error.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                when {
                    state.starting -> {
                        TextButton(onClick = onDismiss) { Text("后台运行") }
                    }
                    state.running -> {
                        TextButton(onClick = { copyAll() }) { Text("复制") }
                        TextButton(onClick = { SshdServer.stop(context) }) { Text("停止") }
                    }
                    else -> {
                        TextButton(
                            onClick = {
                                requestIgnoreBatteryOptimizations(context.findActivity())
                                SshdServer.start(context)
                            },
                        ) { Text("开启 sshd") }
                    }
                }
            }
        },
        dismissButton = {
            if (!state.starting) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun requestIgnoreBatteryOptimizations(activity: Activity?) {
    if (activity == null || Build.VERSION.SDK_INT < 23) return
    val pm = activity.getSystemService(PowerManager::class.java) ?: return
    if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
    try {
        activity.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${activity.packageName}")),
        )
    } catch (_: Exception) {
        try {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}
