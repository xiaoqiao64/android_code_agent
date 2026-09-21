package com.xiaoqiao.codeagent.agent

import android.content.Context
import android.util.Log
import com.xiaoqiao.codeagent.runtime.Proot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

class AgentRunner(private val ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun run(
        agent: AgentSpec,
        prompt: String,
        cwd: String,
        resume: String? = null,
        cfg: AgentRunConfig = AgentRunConfig(),
    ): Flow<AgentEvent> = flow {
        val argv = agent.buildArgv(prompt, resume, cfg)
        emit(AgentEvent.Log("\$ ${argv.joinToString(" ")}"))
        val pb = Proot.processBuilder(ctx, argv, cwd = cwd, wrapUserShell = true)
        val process = try {
            pb.start()
        } catch (e: Exception) {
            emit(AgentEvent.Error("Failed to start agent: ${e.message}"))
            emit(AgentEvent.Finished(false))
            return@flow
        }

        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (coroutineContext.isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("{")) {
                        emit(AgentEvent.Log(trimmed))
                        continue
                    }
                    try {
                        val obj = json.parseToJsonElement(trimmed).jsonObject
                        for (ev in agent.parse(obj)) {
                            emit(ev)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "parse fail: $trimmed", e)
                        emit(AgentEvent.Log(trimmed))
                    }
                }
            }
            val code = process.waitFor()
            if (code != 0) {
                val hint = if (code == 127) " (command not found; is it on PATH / ~/.local/bin?)" else ""
                emit(AgentEvent.Error("Agent exited with code $code$hint"))
                emit(AgentEvent.Finished(false))
            } else {
                emit(AgentEvent.Finished(true))
            }
        } catch (e: Exception) {
            process.destroy()
            emit(AgentEvent.Error(e.message ?: "agent failed"))
            emit(AgentEvent.Finished(false))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "AgentRunner"
    }
}
