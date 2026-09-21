package com.xiaoqiao.codeagent.agent

import android.content.Context
import android.util.Log
import com.xiaoqiao.codeagent.runtime.Proot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ModelLister(private val ctx: Context) {
    suspend fun list(agent: AgentSpec): ModelCatalog = withContext(Dispatchers.IO) {
        var lastText = ""
        for (cmd in agent.listModelsCmds) {
            val text = runCatching { capture(cmd) }.getOrElse { e ->
                Log.w(TAG, "list models failed: ${cmd.joinToString(" ")}", e)
                ""
            }
            lastText = text
            val catalog = ModelCatalogParser.parse(
                text,
                effortViaFlag = agent.effortViaFlag,
                flagEffortLevels = agent.defaultEffortLevels,
            )
            if (catalog.models.size > 1) {
                val efforts = catalog.flagEffortLevels.ifEmpty { agent.defaultEffortLevels }
                return@withContext catalog.copy(flagEffortLevels = efforts)
            }
        }
        val efforts = ModelCatalogParser.parseEffortLevels(lastText).ifEmpty { agent.defaultEffortLevels }
        ModelCatalog.EMPTY.copy(flagEffortLevels = efforts)
    }

    private fun capture(cmd: List<String>): String {
        val pb = Proot.processBuilder(ctx, cmd, wrapUserShell = true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        }
        return out
    }

    companion object {
        private const val TAG = "ModelLister"
    }
}
