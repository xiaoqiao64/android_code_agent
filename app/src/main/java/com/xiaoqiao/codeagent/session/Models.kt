package com.xiaoqiao.codeagent.session

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val role: String, // user | assistant | tool | system
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String? = null,
)

@Serializable
data class AgentSession(
    val id: String,
    val title: String,
    val agentId: String,
    val workspace: String, // path inside debian, e.g. /root/workspace/foo
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val resumeId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
)

@Serializable
data class SessionIndex(
    val sessions: List<SessionSummary> = emptyList(),
    val activeId: String? = null,
)

@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val agentId: String,
    val workspace: String,
    val updatedAt: Long,
)
