package com.xiaoqiao.codeagent.agent

sealed class AgentEvent {
    data class TextDelta(val text: String) : AgentEvent()
    data class AssistantMessage(val text: String) : AgentEvent()
    data class ToolCall(val name: String, val detail: String) : AgentEvent()
    data class SessionId(val id: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class Finished(val success: Boolean) : AgentEvent()
    data class Log(val line: String) : AgentEvent()
}
