package com.xiaoqiao.codeagent.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class AgentRunConfig(
    val model: String? = null,
    val effort: String? = null,
    val thinking: Boolean = false,
    val fast: Boolean = false,
)

data class ModelVariant(
    val slug: String,
    val effort: String? = null,
    val thinking: Boolean = false,
    val fast: Boolean = false,
)

data class AgentModel(
    val id: String,
    val label: String,
    val variants: List<ModelVariant> = listOf(ModelVariant(id.ifBlank { id })),
    val effortViaFlag: Boolean = false,
) {
    val efforts: List<String> get() = variants.mapNotNull { it.effort }.distinct()
    val supportsFast: Boolean get() = variants.any { it.fast } && variants.any { !it.fast }
    val supportsThinking: Boolean get() = variants.any { it.thinking } && variants.any { !it.thinking }
    val showEffort: Boolean get() = effortViaFlag || efforts.size > 1 || (efforts.isNotEmpty() && variants.any { it.effort == null })

    fun resolve(effort: String?, thinking: Boolean, fast: Boolean): String {
        if (id.isEmpty()) return ""
        fun score(v: ModelVariant): Int {
            var s = 0
            if (v.fast == fast) s += 8
            if (effort.isNullOrBlank()) {
                if (v.effort == null) s += 6
            } else if (v.effort == effort) {
                s += 6
            }
            if (v.thinking == thinking) s += 4
            return s
        }
        return variants.maxByOrNull(::score)?.slug ?: variants.first().slug
    }
}

data class ModelCatalog(
    val models: List<AgentModel>,
    val flagEffortLevels: List<String> = emptyList(),
) {
    fun find(modelId: String?): AgentModel? {
        val id = modelId.orEmpty()
        return models.firstOrNull { it.id == id }
            ?: models.firstOrNull { m -> m.variants.any { it.slug == id } }
    }

    fun label(modelId: String?): String {
        val id = modelId.orEmpty()
        return find(id)?.label ?: id.ifBlank { "Default" }
    }

    companion object {
        val EMPTY = ModelCatalog(listOf(AgentModel("", "Default")))
    }
}

object ModelCatalogParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val efforts = listOf("ultracode", "xhigh", "medium", "none", "low", "high", "max")
    private val lineRe = Regex("""^([A-Za-z0-9][A-Za-z0-9._:+-]*)\s+-\s+(.+)$""")
    private val commaListRe = Regex("""Available models:\s*(.+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String, effortViaFlag: Boolean = false, flagEffortLevels: List<String> = emptyList()): ModelCatalog {
        val entries = parseEntries(text)
        val grouped = group(entries, effortViaFlag)
        val models = buildList {
            add(AgentModel("", "Default"))
            addAll(grouped.filter { it.id.isNotEmpty() })
        }
        val effortsFromHelp = parseEffortLevels(text).ifEmpty { flagEffortLevels }
        return ModelCatalog(models = models, flagEffortLevels = effortsFromHelp)
    }

    fun parseEntries(text: String): List<Pair<String, String>> {
        parseJson(text)?.let { if (it.isNotEmpty()) return it }
        val fromLines = parseDashLines(text)
        if (fromLines.isNotEmpty()) return fromLines
        parseCommaList(text)?.let { if (it.isNotEmpty()) return it }
        parseHelpChoices(text)?.let { if (it.isNotEmpty()) return it }
        return parseBareLines(text)
    }

    fun parseEffortLevels(text: String): List<String> {
        val block = Regex("""--effort\b[\s\S]{0,500}""").find(text)?.value ?: return emptyList()
        return efforts.filter { e -> Regex("""\b$e\b""").containsMatchIn(block) }
    }

    fun decompose(slug: String): Pair<String, ModelVariant> {
        val parts = slug.split('-').toMutableList()
        var fast = false
        var thinking = false
        var effort: String? = null
        while (parts.isNotEmpty()) {
            when (val last = parts.last()) {
                "fast" -> if (!fast) {
                    fast = true
                    parts.removeAt(parts.lastIndex)
                } else break
                "thinking" -> if (!thinking) {
                    thinking = true
                    parts.removeAt(parts.lastIndex)
                } else break
                in efforts -> if (effort == null) {
                    effort = last
                    parts.removeAt(parts.lastIndex)
                } else break
                else -> break
            }
        }
        val base = parts.joinToString("-").ifBlank { slug }
        return base to ModelVariant(slug = slug, effort = effort, thinking = thinking, fast = fast)
    }

    fun group(entries: List<Pair<String, String>>, effortViaFlag: Boolean): List<AgentModel> {
        if (entries.isEmpty()) return emptyList()
        data class Row(val label: String, val base: String, val variant: ModelVariant)
        val rows = entries.map { (id, label) ->
            val (base, variant) = decompose(id)
            Row(label, base, variant)
        }
        return rows.groupBy { it.base }.map { (base, group) ->
            val preferred = group.firstOrNull { !it.variant.fast && !it.variant.thinking && it.variant.effort == null }
                ?: group.minByOrNull { it.variant.slug.length }
                ?: group.first()
            AgentModel(
                id = base,
                label = preferred.label.trim(),
                variants = group.map { it.variant }.distinctBy { it.slug },
                effortViaFlag = effortViaFlag,
            )
        }
    }

    private fun parseJson(text: String): List<Pair<String, String>>? {
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val snippet = text.substring(start).trim()
        val el = runCatching { json.parseToJsonElement(snippet) }.getOrNull() ?: return null
        val objs: List<JsonObject> = when (el) {
            is JsonArray -> el.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            is JsonObject -> {
                val arr = el["data"] ?: el["models"] ?: el["result"]
                when {
                    arr is JsonArray -> arr.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                    el["id"] is JsonPrimitive -> listOf(el)
                    else -> el.values.mapNotNull { v ->
                        when (v) {
                            is JsonArray -> v.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                            else -> null
                        }
                    }.flatten().ifEmpty { emptyList() }
                }
            }
            else -> emptyList()
        }
        return objs.mapNotNull { obj ->
            val id = obj.string("id", "slug", "name", "model") ?: return@mapNotNull null
            val label = obj.string("display_name", "displayName", "label", "name") ?: id
            id to label
        }.ifEmpty { null }
    }

    private fun parseDashLines(text: String): List<Pair<String, String>> {
        return text.lineSequence().map { it.trim() }.mapNotNull { line ->
            val m = lineRe.matchEntire(line) ?: return@mapNotNull null
            m.groupValues[1] to m.groupValues[2].trim()
        }.toList()
    }

    private fun parseCommaList(text: String): List<Pair<String, String>>? {
        val m = commaListRe.find(text) ?: return null
        return m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { raw ->
            val id = raw.substringBefore(' ').trim().removeSuffix(",")
            id to id
        }
    }

    private fun parseHelpChoices(text: String): List<Pair<String, String>>? {
        val modelBlock = Regex("""--model\b[\s\S]{0,800}""").find(text)?.value ?: return null
        val choices = Regex("""choices:\s*(.+)""").find(modelBlock)?.groupValues?.get(1) ?: return null
        val ids = Regex(""""([^"]+)"""").findAll(choices).map { it.groupValues[1] }.toList()
        if (ids.isEmpty()) return null
        return ids.map { it to it }
    }

    private fun parseBareLines(text: String): List<Pair<String, String>> {
        return text.lineSequence().map { it.trim() }
            .filter { it.matches(Regex("""[A-Za-z0-9][A-Za-z0-9._:+-]*""")) }
            .filterNot { it.equals("Available", ignoreCase = true) }
            .map { it to it }
            .toList()
    }

    private fun JsonObject.string(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            val s = v.contentOrNull?.trim().orEmpty()
            if (s.isNotEmpty()) return s
        }
        return null
    }
}
