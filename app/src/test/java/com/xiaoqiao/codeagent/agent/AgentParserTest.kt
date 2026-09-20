package com.xiaoqiao.codeagent.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentParserTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(agent: AgentSpec, line: String): List<AgentEvent> {
        val obj = json.parseToJsonElement(line).jsonObject
        return agent.parse(obj)
    }

    @Test
    fun claudeResultEmitsSessionAndFinished() {
        val line = """{"type":"result","subtype":"success","result":"Hello","session_id":"sess-1","is_error":false}"""
        val events = parse(Agents.claude, line)
        assertTrue(events.any { it is AgentEvent.SessionId && it.id == "sess-1" })
        assertTrue(events.any { it is AgentEvent.AssistantMessage && it.text == "Hello" })
        assertTrue(events.any { it is AgentEvent.Finished && it.success })
    }

    @Test
    fun claudeAssistantTextDelta() {
        val line = """{"type":"assistant","message":{"content":[{"type":"text","text":"Hi"}]}}"""
        val events = parse(Agents.claude, line)
        assertEquals(listOf(AgentEvent.TextDelta("Hi")), events)
    }

    @Test
    fun codexThreadStarted() {
        val line = """{"type":"thread.started","thread_id":"thr-9"}"""
        val events = parse(Agents.codex, line)
        assertEquals(listOf(AgentEvent.SessionId("thr-9")), events)
    }

    @Test
    fun codexAgentMessage() {
        val line = """{"type":"item.completed","item":{"id":"i1","type":"agent_message","text":"Done"}}"""
        val events = parse(Agents.codex, line)
        assertEquals(listOf(AgentEvent.TextDelta("Done")), events)
    }

    @Test
    fun cursorStreamingDelta() {
        val line = """{"type":"assistant","timestamp_ms":1,"message":{"content":[{"type":"text","text":"Hi"}]}}"""
        val events = parse(Agents.cursor, line)
        assertEquals(listOf(AgentEvent.TextDelta("Hi")), events)
    }

    @Test
    fun cursorBufferedFlushSkipped() {
        val line = """{"type":"assistant","model_call_id":"x","timestamp_ms":1,"message":{"content":[{"type":"text","text":"dup"}]}}"""
        val events = parse(Agents.cursor, line)
        assertTrue(events.isEmpty())
    }

    @Test
    fun cursorResult() {
        val line = """{"type":"result","subtype":"success","result":"Final","session_id":"c1"}"""
        val events = parse(Agents.cursor, line)
        assertTrue(events.any { it is AgentEvent.SessionId && it.id == "c1" })
        assertTrue(events.any { it is AgentEvent.AssistantMessage && it.text == "Final" })
        assertTrue(events.any { it is AgentEvent.Finished && it.success })
    }

    @Test
    fun copilotAssistantMessage() {
        val line = """{"type":"assistant.message","text":"Hello from copilot"}"""
        val events = parse(Agents.copilot, line)
        assertEquals(listOf(AgentEvent.TextDelta("Hello from copilot")), events)
    }

    @Test
    fun copilotTaskComplete() {
        val line = """{"type":"session.task_complete","summary":"All done"}"""
        val events = parse(Agents.copilot, line)
        assertTrue(events.any { it is AgentEvent.AssistantMessage && it.text == "All done" })
        assertTrue(events.any { it is AgentEvent.Finished && it.success })
    }
}
