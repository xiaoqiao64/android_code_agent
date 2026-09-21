package com.xiaoqiao.codeagent.session

import android.content.Context
import com.xiaoqiao.codeagent.runtime.Bootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class SessionStore(private val ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val root: File get() = File(ctx.filesDir, "sessions").also { it.mkdirs() }
    private val indexFile: File get() = File(root, "index.json")

    suspend fun list(): SessionIndex = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext SessionIndex()
        runCatching { json.decodeFromString<SessionIndex>(indexFile.readText()) }
            .getOrElse { SessionIndex() }
    }

    suspend fun load(id: String): AgentSession? = withContext(Dispatchers.IO) {
        val f = File(root, "$id.json")
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString<AgentSession>(f.readText()) }.getOrNull()
    }

    suspend fun save(session: AgentSession) = withContext(Dispatchers.IO) {
        File(root, "${session.id}.json").writeText(json.encodeToString(session))
        val idx = list().let { current ->
            val summary = SessionSummary(
                id = session.id,
                title = session.title,
                agentId = session.agentId,
                workspace = session.workspace,
                updatedAt = session.updatedAt,
            )
            val others = current.sessions.filterNot { it.id == session.id }
            SessionIndex(
                sessions = (listOf(summary) + others).sortedByDescending { it.updatedAt },
                activeId = session.id,
            )
        }
        indexFile.writeText(json.encodeToString(idx))
    }

    suspend fun setActive(id: String?) = withContext(Dispatchers.IO) {
        val idx = list().copy(activeId = id)
        indexFile.writeText(json.encodeToString(idx))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, "$id.json").delete()
        val idx = list()
        val next = idx.copy(
            sessions = idx.sessions.filterNot { it.id == id },
            activeId = if (idx.activeId == id) idx.sessions.firstOrNull { it.id != id }?.id else idx.activeId,
        )
        indexFile.writeText(json.encodeToString(next))
    }

    suspend fun create(
        agentId: String,
        workspace: String,
        title: String? = null,
        modelId: String? = null,
    ): AgentSession {
        val id = UUID.randomUUID().toString()
        val session = AgentSession(
            id = id,
            title = title ?: workspace.substringAfterLast('/').ifBlank { "New session" },
            agentId = agentId,
            workspace = workspace,
            modelId = modelId,
        )
        save(session)
        return session
    }

    fun listWorkspaces(): List<String> {
        val dir = Bootstrap.workspaceHostDir(ctx)
        dir.mkdirs()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { "/root/workspace/${it.name}" }
            ?.sorted()
            .orEmpty()
    }

    fun createWorkspace(name: String): String {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
        val dir = File(Bootstrap.workspaceHostDir(ctx), safe)
        dir.mkdirs()
        return "/root/workspace/$safe"
    }
}
