package com.xiaoqiao.codeagent.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AgentSpec(
    val id: String,
    val label: String,
    val probeCmd: List<String>,
    val installCmd: String,
    val listModelsCmds: List<List<String>>,
    val effortViaFlag: Boolean = false,
    val defaultEffortLevels: List<String> = emptyList(),
    val buildArgv: (prompt: String, resume: String?, cfg: AgentRunConfig) -> List<String>,
    val parse: (JsonObject) -> List<AgentEvent>,
)

object Agents {
    val claude = AgentSpec(
        id = "claude",
        label = "Claude Code",
        probeCmd = listOf("claude", "--version"),
        installCmd = "npm install -g @anthropic-ai/claude-code",
        listModelsCmds = listOf(
            listOf("claude", "--help"),
        ),
        effortViaFlag = true,
        defaultEffortLevels = listOf("low", "medium", "high", "xhigh", "max"),
        buildArgv = { prompt, resume, cfg ->
            buildList {
                addAll(listOf("claude", "-p", prompt, "--output-format", "stream-json", "--verbose", "--permission-mode", "bypassPermissions"))
                addModel(cfg.model)
                if (!cfg.effort.isNullOrBlank()) {
                    add("--effort")
                    add(cfg.effort)
                }
                if (!resume.isNullOrBlank()) {
                    add("--resume")
                    add(resume)
                }
            }
        },
        parse = ::parseClaude,
    )

    val codex = AgentSpec(
        id = "codex",
        label = "Codex",
        probeCmd = listOf("codex", "--version"),
        installCmd = "npm install -g @openai/codex",
        listModelsCmds = listOf(
            listOf("codex", "debug", "models"),
            listOf("codex", "--help"),
        ),
        effortViaFlag = true,
        defaultEffortLevels = listOf("minimal", "low", "medium", "high", "xhigh"),
        buildArgv = { prompt, resume, cfg ->
            buildList {
                if (!resume.isNullOrBlank()) {
                    addAll(listOf("codex", "exec", "resume", resume, "--json", "--dangerously-bypass-approvals-and-sandbox"))
                } else {
                    addAll(listOf("codex", "exec", "--json", "--dangerously-bypass-approvals-and-sandbox"))
                }
                addModel(cfg.model)
                if (!cfg.effort.isNullOrBlank()) {
                    add("--config")
                    add("model_reasoning_effort=${cfg.effort}")
                }
                if (resume.isNullOrBlank()) add(prompt)
            }
        },
        parse = ::parseCodex,
    )

    val cursor = AgentSpec(
        id = "cursor",
        label = "Cursor Agent",
        probeCmd = listOf("cursor-agent", "--version"),
        installCmd = "curl https://cursor.com/install -fsS | bash",
        listModelsCmds = listOf(
            listOf("cursor-agent", "--list-models", "--trust"),
            listOf("cursor-agent", "models", "--trust"),
            listOf("cursor-agent", "--help"),
        ),
        buildArgv = { prompt, resume, cfg ->
            buildList {
                addAll(
                    listOf(
                        "cursor-agent",
                        "-p",
                        prompt,
                        "--output-format",
                        "stream-json",
                        "--stream-partial-output",
                        "--force",
                        "--trust",
                    ),
                )
                addModel(cfg.model)
                if (!resume.isNullOrBlank()) add("--resume=$resume")
            }
        },
        parse = ::parseCursor,
    )

    val copilot = AgentSpec(
        id = "copilot",
        label = "GitHub Copilot",
        probeCmd = listOf("copilot", "--version"),
        installCmd = "npm install -g @github/copilot",
        listModelsCmds = listOf(
            listOf("copilot", "/model", "--list", "--json"),
            listOf("copilot", "--help"),
        ),
        buildArgv = { prompt, resume, cfg ->
            buildList {
                addAll(listOf("copilot", "-p", prompt, "--allow-all-tools", "--output-format=json"))
                addModel(cfg.model)
                if (!resume.isNullOrBlank()) add("--resume=$resume")
            }
        },
        parse = ::parseCopilot,
    )

    val all: List<AgentSpec> = listOf(claude, codex, cursor, copilot)

    fun byId(id: String): AgentSpec = all.firstOrNull { it.id == id } ?: claude
}

private fun MutableList<String>.addModel(model: String?) {
    if (!model.isNullOrBlank()) {
        add("--model")
        add(model)
    }
}

private fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = this[k] ?: continue
        return v.jsonPrimitive.contentOrNull ?: continue
    }
    return null
}

private fun parseClaude(obj: JsonObject): List<AgentEvent> {
    val type = obj.str("type") ?: return emptyList()
    return when (type) {
        "system" -> {
            val sid = obj.str("session_id")
            if (sid != null) listOf(AgentEvent.SessionId(sid)) else emptyList()
        }
        "assistant" -> {
            val message = obj["message"]?.jsonObject
            val content = message?.get("content")?.jsonArray
            val texts = content?.mapNotNull { block ->
                val b = block.jsonObject
                if (b.str("type") == "text") b.str("text") else null
            }.orEmpty()
            texts.map { AgentEvent.TextDelta(it) }
        }
        "content_block_delta" -> {
            val delta = obj["delta"]?.jsonObject
            val text = delta?.str("text")
            if (text != null) listOf(AgentEvent.TextDelta(text)) else emptyList()
        }
        "result" -> {
            val result = obj.str("result").orEmpty()
            val sid = obj.str("session_id")
            buildList {
                if (sid != null) add(AgentEvent.SessionId(sid))
                if (result.isNotBlank()) add(AgentEvent.AssistantMessage(result))
                add(AgentEvent.Finished(obj.str("is_error") != "true"))
            }
        }
        "error" -> listOf(AgentEvent.Error(obj.str("error", "message") ?: obj.toString()))
        else -> emptyList()
    }
}

private fun parseCodex(obj: JsonObject): List<AgentEvent> {
    val type = obj.str("type") ?: return emptyList()
    return when (type) {
        "thread.started", "session.created" -> {
            val sid = obj.str("thread_id", "session_id")
            if (sid != null) listOf(AgentEvent.SessionId(sid)) else emptyList()
        }
        "item.completed", "item.updated" -> {
            val item = obj["item"]?.jsonObject ?: return emptyList()
            when (item.str("type")) {
                "agent_message" -> listOf(AgentEvent.TextDelta(item.str("text").orEmpty()))
                "command_execution" -> listOf(
                    AgentEvent.ToolCall(
                        "command",
                        item.str("command").orEmpty(),
                    ),
                )
                "file_change" -> listOf(AgentEvent.ToolCall("file_change", item.str("path", "changes").orEmpty()))
                else -> emptyList()
            }
        }
        "turn.completed" -> listOf(AgentEvent.Finished(true))
        "turn.failed", "error" -> listOf(AgentEvent.Error(obj.str("error", "message") ?: "codex failed"))
        else -> emptyList()
    }
}

private fun parseCursor(obj: JsonObject): List<AgentEvent> {
    val type = obj.str("type") ?: return emptyList()
    return when (type) {
        "system" -> {
            val sid = obj.str("session_id")
            if (sid != null) listOf(AgentEvent.SessionId(sid)) else emptyList()
        }
        "assistant" -> {
            val message = obj["message"]?.jsonObject
            val content = message?.get("content")?.jsonArray
            val texts = content?.mapNotNull { block ->
                val b = block.jsonObject
                b.str("text")
            }.orEmpty()
            // Prefer streaming deltas that have timestamp_ms and no model_call_id
            val hasTs = obj.containsKey("timestamp_ms")
            val hasMc = obj.containsKey("model_call_id")
            if (hasTs && !hasMc) texts.map { AgentEvent.TextDelta(it) }
            else emptyList()
        }
        "tool_call" -> {
            if (obj.str("subtype") == "completed") emptyList()
            else {
                val tool = obj["tool_call"]?.jsonObject
                listOf(AgentEvent.ToolCall("tool", cursorToolSummary(tool)))
            }
        }
        "result" -> {
            val sid = obj.str("session_id")
            val result = obj.str("result").orEmpty()
            buildList {
                if (sid != null) add(AgentEvent.SessionId(sid))
                // Prefer streamed deltas; only use the terminal result if nothing was streamed.
                if (result.isNotBlank()) add(AgentEvent.AssistantMessage(result))
                add(AgentEvent.Finished(obj.str("subtype") != "error"))
            }
        }
        else -> emptyList()
    }
}

private fun cursorToolSummary(tool: JsonObject?): String {
    if (tool == null) return "tool_call"
    val name = tool.keys.firstOrNull() ?: return "tool_call"
    val args = tool[name]?.jsonObject?.get("args")?.jsonObject
    val path = args?.str("path", "command", "query")
    return if (path.isNullOrBlank()) name else "$name $path"
}

private fun parseCopilot(obj: JsonObject): List<AgentEvent> {
    val type = obj.str("type", "kind", "event") ?: return emptyList()
    return when {
        type.contains("session") && obj.str("session_id", "id") != null ->
            listOf(AgentEvent.SessionId(obj.str("session_id", "id")!!))
        type.contains("assistant") || type == "message" || type == "assistant.message" -> {
            val text = obj.str("text", "content", "message").orEmpty()
            if (text.isNotBlank()) listOf(AgentEvent.TextDelta(text)) else emptyList()
        }
        type.contains("tool") -> listOf(AgentEvent.ToolCall("tool", obj.str("name", "tool").orEmpty()))
        type.contains("result") || type.contains("complete") || type == "session.task_complete" -> {
            val text = obj.str("summary", "text", "result").orEmpty()
            buildList {
                if (text.isNotBlank()) add(AgentEvent.AssistantMessage(text))
                add(AgentEvent.Finished(true))
            }
        }
        type.contains("error") -> listOf(AgentEvent.Error(obj.str("message", "error") ?: "copilot error"))
        else -> emptyList()
    }
}
