package dev.dshn.app.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class ContextBreakdown(
    val systemTokens: Long = 0L,
    val toolsTokens: Long = 0L,
    val messageTokens: Long = 0L
) {
    val totalTokens: Long get() = systemTokens + toolsTokens + messageTokens
}

@Serializable
data class ContextTokenUsage(
    val uncachedInputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val cacheHitRate: Double? = null
) {
    val totalInputTokens: Long get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens

    fun resolvedCacheHitRate(): Double? {
        cacheHitRate?.let { if (!it.isNaN() && !it.isInfinite()) return it.coerceIn(0.0, 1.0) }
        val total = totalInputTokens
        return if (total > 0L) (cacheReadTokens.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) else null
    }
}

@Serializable
data class ContextSectionInfo(
    val name: String,
    val chars: Int = 0,
    val preview: String = "",
    val truncated: Boolean = false
)

@Serializable
data class ContextSystemPromptInfo(
    val totalChars: Int = 0,
    val estimatedTokens: Long = 0L,
    val sections: List<ContextSectionInfo> = emptyList(),
    val contexts: List<ContextSectionInfo> = emptyList()
)

@Serializable
data class ContextToolInfo(
    val name: String,
    val description: String = "",
    val source: String = "builtin",
    val deferLoading: Boolean = false
)

@Serializable
data class ContextSkillInfo(
    val name: String,
    val description: String = "",
    val whenToUse: String? = null,
    val provider: String = "unknown",
    val path: String? = null,
    val modelInvocable: Boolean = true,
    val userInvocable: Boolean = true
)

@Serializable
data class ContextInspectSnapshot(
    val sessionId: String? = null,
    val cwd: String? = null,
    val hasLiveAgent: Boolean = false,
    val usedTokens: Long = 0L,
    val contextWindow: Long = 0L,
    val breakdown: ContextBreakdown = ContextBreakdown(),
    val tokenUsage: ContextTokenUsage = ContextTokenUsage(),
    val lastTurnUsage: ContextTokenUsage? = null,
    val systemPrompt: ContextSystemPromptInfo = ContextSystemPromptInfo(),
    val tools: List<ContextToolInfo> = emptyList(),
    val skills: List<ContextSkillInfo> = emptyList()
)

@Serializable
data class PluginRowInfo(
    val id: String,
    val name: String,
    val enabled: Boolean = true
)

@Serializable
data class PluginBundleInfo(
    val name: String,
    val displayName: String,
    val description: String = "",
    val version: String? = null,
    val availability: String = "missing",
    val official: Boolean = false,
    val optional: Boolean = false,
    val enabled: Boolean = false,
    val installed: Boolean = false,
    val removable: Boolean = false,
    val readOnlyReason: String? = null,
    val listedByDefault: Boolean = true,
    val pluginCount: Int = 0,
    val plugins: List<PluginRowInfo> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    val isProtected: Boolean get() = !readOnlyReason.isNullOrBlank()
    val isOnDemandMissing: Boolean get() = availability == "missing" && !installed
    val isShippedBuiltIn: Boolean get() = availability == "installation"
    val isProfileInstalled: Boolean get() = availability == "profile" || (installed && !isShippedBuiltIn)
}

@Serializable
data class PluginsListSnapshot(
    val ok: Boolean = false,
    val available: Boolean = false,
    val hmrEnabled: Boolean = false,
    val pnpmAvailable: Boolean = false,
    val profile: String = "acp",
    val bundles: List<PluginBundleInfo> = emptyList(),
    val error: String? = null
)

@Serializable
data class PluginActionResult(
    val ok: Boolean = false,
    val name: String? = null,
    val application: String = "unknown",
    val restartRequired: Boolean = false,
    val missingPnpm: Boolean = false,
    val diagnostic: String? = null
)

object BridgePayloadParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseContextInspect(rawJson: String): ContextInspectSnapshot {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        return parseContextInspect(obj)
    }

    fun parseContextInspect(obj: JsonObject): ContextInspectSnapshot {
        fun parseUsage(uObj: JsonObject?): ContextTokenUsage {
            if (uObj == null) return ContextTokenUsage()
            return ContextTokenUsage(
                uncachedInputTokens = uObj["uncachedInputTokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                outputTokens = uObj["outputTokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                cacheReadTokens = uObj["cacheReadTokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                cacheWriteTokens = uObj["cacheWriteTokens"]?.jsonPrimitive?.longOrNull ?: 0L,
                cacheHitRate = uObj["cacheHitRate"]?.jsonPrimitive?.doubleOrNull
            )
        }

        fun parseSections(key: String, parent: JsonObject?): List<ContextSectionInfo> {
            val arr = runCatching { parent?.get(key)?.jsonArray }.getOrNull().orEmpty()
            return arr.mapNotNull { elem ->
                val o = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ContextSectionInfo(
                    name = name,
                    chars = o["chars"]?.jsonPrimitive?.intOrNull ?: 0,
                    preview = o["preview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    truncated = o["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }
        }

        val brObj = runCatching { obj["breakdown"]?.jsonObject }.getOrNull()
        val spObj = runCatching { obj["systemPrompt"]?.jsonObject }.getOrNull()
        val lastUsageObj = runCatching { obj["lastTurnUsage"]?.jsonObject }.getOrNull()

        val tools = runCatching { obj["tools"]?.jsonArray }.getOrNull().orEmpty().mapNotNull { elem ->
            val o = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ContextToolInfo(
                name = name,
                description = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                source = o["source"]?.jsonPrimitive?.contentOrNull ?: "builtin",
                deferLoading = o["deferLoading"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }

        val skills = runCatching { obj["skills"]?.jsonArray }.getOrNull().orEmpty().mapNotNull { elem ->
            val o = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ContextSkillInfo(
                name = name,
                description = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                whenToUse = o["whenToUse"]?.jsonPrimitive?.contentOrNull,
                provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                path = o["path"]?.jsonPrimitive?.contentOrNull,
                modelInvocable = o["modelInvocable"]?.jsonPrimitive?.booleanOrNull ?: true,
                userInvocable = o["userInvocable"]?.jsonPrimitive?.booleanOrNull ?: true
            )
        }

        return ContextInspectSnapshot(
            sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull,
            cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull,
            hasLiveAgent = obj["hasLiveAgent"]?.jsonPrimitive?.booleanOrNull ?: false,
            usedTokens = obj["usedTokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            contextWindow = obj["contextWindow"]?.jsonPrimitive?.longOrNull ?: 0L,
            breakdown = ContextBreakdown(
                systemTokens = brObj?.get("systemTokens")?.jsonPrimitive?.longOrNull ?: 0L,
                toolsTokens = brObj?.get("toolsTokens")?.jsonPrimitive?.longOrNull ?: 0L,
                messageTokens = brObj?.get("messageTokens")?.jsonPrimitive?.longOrNull ?: 0L
            ),
            tokenUsage = parseUsage(runCatching { obj["tokenUsage"]?.jsonObject }.getOrNull()),
            lastTurnUsage = lastUsageObj?.let { parseUsage(it) },
            systemPrompt = ContextSystemPromptInfo(
                totalChars = spObj?.get("totalChars")?.jsonPrimitive?.intOrNull ?: 0,
                estimatedTokens = spObj?.get("estimatedTokens")?.jsonPrimitive?.longOrNull ?: 0L,
                sections = parseSections("sections", spObj),
                contexts = parseSections("contexts", spObj)
            ),
            tools = tools,
            skills = skills
        )
    }

    fun parsePluginsList(rawJson: String): PluginsListSnapshot =
        json.decodeFromString(PluginsListSnapshot.serializer(), rawJson)

    fun parsePluginAction(rawJson: String): PluginActionResult =
        json.decodeFromString(PluginActionResult.serializer(), rawJson)
}
