package com.xiaoqiao.codeagent.runtime

data class SshdInfo(
    val user: String = "root",
    val password: String,
    val port: Int = PORT,
    val ips: List<String>,
) {
    fun primaryIp(): String = ips.firstOrNull() ?: "127.0.0.1"

    fun command(): String = "ssh $user@${primaryIp()} -p $port"

    fun copyText(): String = buildString {
        appendLine(command())
        appendLine("user: $user")
        appendLine("password: $password")
        appendLine("port: $port")
        if (ips.isEmpty()) {
            appendLine("ip: (none — 连上 Wi-Fi 或开热点后再试)")
        } else {
            appendLine("ip: ${ips.joinToString(", ")}")
        }
    }.trimEnd()

    companion object {
        const val PORT = 8022
    }
}

data class SshdUiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val log: String = "",
    val error: String? = null,
    val info: SshdInfo? = null,
)
