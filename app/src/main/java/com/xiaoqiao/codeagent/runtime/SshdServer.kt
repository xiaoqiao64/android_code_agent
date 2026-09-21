package com.xiaoqiao.codeagent.runtime

import android.content.Context
import android.util.Log
import com.xiaoqiao.codeagent.agent.AgentForegroundService
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * OpenSSH inside Debian proot. Port 22 is not bindable on unrooted Android, so we use 8022.
 * The Java Process must stay alive (proot `--kill-on-exit`); [AgentForegroundService] holds it.
 */
object SshdServer {
    private const val TAG = "SshdServer"
    private const val PREFS = "sshd"
    private const val KEY_PASSWORD = "password"
    private const val KEY_WANTED = "wanted"

    private val _state = MutableStateFlow(SshdUiState())
    val state: StateFlow<SshdUiState> = _state.asStateFlow()

    val info: SshdInfo? get() = _state.value.info

    private var process: Process? = null
    private val startingGate = AtomicBoolean(false)
    @Volatile private var userStop = false
    private val logLock = Any()
    private val logLines = ArrayDeque<String>()

    fun isRunning(): Boolean = process?.isAlive == true

    fun isWanted(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WANTED, false)

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (isRunning()) {
            _state.update { it.copy(running = true, starting = false, error = null, info = info ?: it.info) }
            AgentForegroundService.start(app, AgentForegroundService.REASON_SSHD)
            return
        }
        if (!startingGate.compareAndSet(false, true)) return
        userStop = false
        setWanted(app, true)
        _state.update { it.copy(starting = true, running = false, error = null) }
        thread(name = "sshd-start") {
            try {
                Bootstrap.ensureBootstrap(app)
                if (!Bootstrap.debianDir(app).exists()) {
                    throw IllegalStateException("Debian rootfs missing — finish setup first")
                }
                val password = password(app)
                val ips = localIpv4()
                val scriptFile = File(Bootstrap.debianDir(app), "root/start_sshd.sh")
                scriptFile.parentFile?.mkdirs()
                scriptFile.writeText(startScript(password, SshdInfo.PORT))
                scriptFile.setExecutable(true)

                val pb = Proot.processBuilder(app, listOf("bash", "/root/start_sshd.sh"), cwd = "/root")
                val proc = pb.start()
                if (userStop) {
                    proc.destroy()
                    setWanted(app, false)
                    AgentForegroundService.stop(app, AgentForegroundService.REASON_SSHD)
                    _state.update { SshdUiState() }
                    return@thread
                }
                process = proc
                AgentForegroundService.start(app, AgentForegroundService.REASON_SSHD)

                var ready = false
                val errBuf = StringBuilder()
                proc.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        appendLog(line)
                        errBuf.appendLine(line)
                        _state.update { it.copy(log = snapshotLog()) }
                        if (!ready && line.contains("Server listening", ignoreCase = true)) {
                            ready = true
                            val snapshot = SshdInfo(password = password, ips = ips)
                            _state.update {
                                it.copy(running = true, starting = false, info = snapshot, error = null)
                            }
                            AgentForegroundService.update(app)
                        }
                    }
                }
                val code = proc.waitFor()
                process = null
                if (userStop) {
                    setWanted(app, false)
                    _state.update { SshdUiState() }
                } else if (!ready) {
                    setWanted(app, false)
                    val msg = "sshd exited $code\n${errBuf.toString().takeLast(2000)}"
                    _state.update { it.copy(running = false, starting = false, info = null, error = msg) }
                } else {
                    setWanted(app, false)
                    _state.update {
                        it.copy(running = false, starting = false, info = null, error = "sshd stopped")
                    }
                }
                AgentForegroundService.stop(app, AgentForegroundService.REASON_SSHD)
            } catch (e: Exception) {
                Log.e(TAG, "sshd start failed", e)
                process = null
                setWanted(app, false)
                AgentForegroundService.stop(app, AgentForegroundService.REASON_SSHD)
                _state.update {
                    it.copy(running = false, starting = false, info = null, error = e.message ?: "sshd failed")
                }
            } finally {
                startingGate.set(false)
            }
        }
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        userStop = true
        setWanted(app, false)
        process?.destroy()
        process = null
        AgentForegroundService.stop(app, AgentForegroundService.REASON_SSHD)
        _state.update { SshdUiState() }
    }

    fun localIpv4(): List<String> {
        val lan = mutableListOf<String>()
        val extras = mutableListOf<String>()
        val en = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (en.hasMoreElements()) {
            val nif = en.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    val ip = a.hostAddress ?: continue
                    if (isPrivateV4(ip)) lan += ip else extras += ip
                }
            }
        }
        return (lan + extras).distinct()
    }

    private fun isPrivateV4(ip: String): Boolean {
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true
        if (!ip.startsWith("172.")) return false
        val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }

    private fun setWanted(ctx: Context, wanted: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WANTED, wanted).apply()
    }

    private fun password(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_PASSWORD, null)
        if (!existing.isNullOrBlank()) return existing
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rng = SecureRandom()
        val pw = CharArray(12) { alphabet[rng.nextInt(alphabet.length)] }.concatToString()
        prefs.edit().putString(KEY_PASSWORD, pw).apply()
        return pw
    }

    private fun appendLog(line: String) {
        synchronized(logLock) {
            logLines.addLast(line)
            while (logLines.size > 80) logLines.removeFirst()
        }
    }

    private fun snapshotLog(): String = synchronized(logLock) { logLines.joinToString("\n") }

    private fun startScript(password: String, port: Int): String = """
        #!/bin/bash
        set -e
        export DEBIAN_FRONTEND=noninteractive
        export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        if ! command -v sshd >/dev/null 2>&1; then
          echo "Installing openssh-server…"
          apt-get update
          apt-get install -y --no-install-recommends openssh-server
        fi
        mkdir -p /run/sshd /etc/ssh
        chmod 755 /run/sshd
        ssh-keygen -A
        echo "root:$password" | chpasswd
        CONF=/etc/ssh/sshd_code_agent.conf
        {
          echo "Port $port"
          echo "ListenAddress 0.0.0.0"
          echo "PermitRootLogin yes"
          echo "PasswordAuthentication yes"
          echo "PubkeyAuthentication yes"
          echo "KbdInteractiveAuthentication no"
          echo "UsePAM no"
          echo "X11Forwarding no"
          echo "PrintMotd no"
          echo "StrictModes no"
          echo "PidFile /run/sshd.pid"
          echo "Subsystem sftp /usr/lib/openssh/sftp-server"
          for k in /etc/ssh/ssh_host_ed25519_key /etc/ssh/ssh_host_rsa_key /etc/ssh/ssh_host_ecdsa_key; do
            [ -f "${'$'}k" ] && echo "HostKey ${'$'}k"
          done
        } > "${'$'}CONF"
        pkill -x sshd >/dev/null 2>&1 || true
        sleep 0.3
        echo "Starting sshd on port $port…"
        exec /usr/sbin/sshd -D -e -f "${'$'}CONF"
    """.trimIndent()
}
