package com.xiaoqiao.codeagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun parsesDashCatalog() {
        val text = """
            Available models

            auto - Auto (default)
            gpt-5.3-codex - Codex 5.3
            gpt-5.3-codex-fast - Codex 5.3 Fast
            gpt-5.3-codex-high - Codex 5.3 High
            gpt-5.3-codex-high-fast - Codex 5.3 High Fast
            claude-opus-4-8-thinking-high - Opus 4.8 Thinking High
            claude-opus-4-8-thinking-high-fast - Opus 4.8 1M Thinking Fast
            composer-2 - Composer 2
            composer-2-fast - Composer 2 Fast
        """.trimIndent()
        val catalog = ModelCatalogParser.parse(text)
        val ids = catalog.models.map { it.id }
        assertTrue("Default" in catalog.models.map { it.label })
        assertTrue("auto" in ids)
        val codex = catalog.find("gpt-5.3-codex")!!
        assertTrue(codex.supportsFast)
        assertTrue(codex.efforts.contains("high"))
        assertEquals("gpt-5.3-codex-high-fast", codex.resolve("high", false, true))
        val composer = catalog.find("composer-2")!!
        assertTrue(composer.supportsFast)
        assertEquals("composer-2-fast", composer.resolve(null, false, true))
        val opus = catalog.find("claude-opus-4-8")!!
        assertTrue(opus.supportsThinking || opus.variants.any { it.thinking })
        assertEquals("claude-opus-4-8-thinking-high-fast", opus.resolve("high", true, true))
    }

    @Test
    fun parsesCommaList() {
        val text = "Cannot use this model. Available models: auto, composer-2, composer-2-fast, gpt-5.4-high"
        val catalog = ModelCatalogParser.parse(text)
        assertTrue(catalog.find("auto") != null)
        assertTrue(catalog.find("composer-2")!!.supportsFast)
        assertTrue(catalog.find("gpt-5.4")!!.efforts.contains("high"))
    }

    @Test
    fun parsesJsonArray() {
        val text = """[{"id":"claude-sonnet-4.6","name":"Claude Sonnet 4.6"},{"id":"gpt-5.5","name":"GPT-5.5"}]"""
        val catalog = ModelCatalogParser.parse(text)
        assertEquals("Claude Sonnet 4.6", catalog.find("claude-sonnet-4.6")?.label)
        assertEquals("GPT-5.5", catalog.find("gpt-5.5")?.label)
    }

    @Test
    fun parsesHelpChoicesAndEffort() {
        val text = """
            --model <model>  Model to use (choices: "sonnet", "opus", "haiku")
            --effort         Set the effort level. Options: low, medium, high, xhigh, max
        """.trimIndent()
        val catalog = ModelCatalogParser.parse(text, effortViaFlag = true)
        assertTrue(catalog.find("sonnet") != null)
        assertTrue(catalog.flagEffortLevels.containsAll(listOf("low", "medium", "high", "max")))
        assertTrue(catalog.find("sonnet")!!.effortViaFlag)
    }

    @Test
    fun leavesCodexMaxAsBaseName() {
        val (base, variant) = ModelCatalogParser.decompose("gpt-5.1-codex-max-high-fast")
        assertEquals("gpt-5.1-codex-max", base)
        assertEquals("high", variant.effort)
        assertTrue(variant.fast)
    }
}
