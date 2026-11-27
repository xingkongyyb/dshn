package dev.dshn.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAndPluginModelsTest {

    @Test
    fun parseContextInspectAndComputeCacheHitRate() {
        val raw = """
            {
              "ok": true,
              "sessionId": "sess-123",
              "cwd": "/root",
              "hasLiveAgent": true,
              "usedTokens": 4820,
              "contextWindow": 131072,
              "breakdown": {
                "systemTokens": 1800,
                "toolsTokens": 1200,
                "messageTokens": 1820
              },
              "tokenUsage": {
                "uncachedInputTokens": 1000,
                "outputTokens": 450,
                "cacheReadTokens": 9000,
                "cacheWriteTokens": 0
              },
              "lastTurnUsage": {
                "uncachedInputTokens": 200,
                "outputTokens": 120,
                "cacheReadTokens": 1800,
                "cacheWriteTokens": 0,
                "cacheHitRate": 0.9
              },
              "systemPrompt": {
                "totalChars": 6400,
                "estimatedTokens": 1800,
                "sections": [
                  { "name": "harness:identity", "chars": 400, "preview": "You are DSH.", "truncated": false }
                ],
                "contexts": [
                  { "name": "working-directory", "chars": 24, "preview": "/root", "truncated": false }
                ]
              },
              "tools": [
                { "name": "bash", "description": "Run shell commands", "source": "builtin", "deferLoading": false },
                { "name": "dshn-device-bridge__device_info", "description": "Read Android device info", "source": "mcp", "deferLoading": false }
              ],
              "skills": [
                {
                  "name": "office-docx",
                  "description": "Create and edit Word documents",
                  "provider": "dsh-office",
                  "path": "/opt/node24/skills/office-docx",
                  "modelInvocable": true,
                  "userInvocable": true
                }
              ]
            }
        """.trimIndent()

        val snap = BridgePayloadParsers.parseContextInspect(raw)
        assertEquals("sess-123", snap.sessionId)
        assertEquals(4820L, snap.usedTokens)
        assertEquals(131072L, snap.contextWindow)
        assertEquals(4820L, snap.breakdown.totalTokens)
        assertEquals(10000L, snap.tokenUsage.totalInputTokens)
        assertEquals(0.90, snap.tokenUsage.resolvedCacheHitRate()!!, 1e-6)
        assertNotNull(snap.lastTurnUsage)
        assertEquals(0.90, snap.lastTurnUsage!!.resolvedCacheHitRate()!!, 1e-6)
        assertEquals(1, snap.systemPrompt.sections.size)
        assertEquals("harness:identity", snap.systemPrompt.sections.first().name)
        assertEquals(1, snap.systemPrompt.contexts.size)
        assertEquals(2, snap.tools.size)
        assertEquals("mcp", snap.tools[1].source)
        assertEquals(1, snap.skills.size)
        assertEquals("office-docx", snap.skills.first().name)
    }

    @Test
    fun zeroInputUsageReturnsNullCacheHitRate() {
        val empty = ContextTokenUsage()
        assertNull(empty.resolvedCacheHitRate())
        val withWrite = ContextTokenUsage(uncachedInputTokens = 100, cacheReadTokens = 600, cacheWriteTokens = 300)
        assertEquals(0.6, withWrite.resolvedCacheHitRate()!!, 1e-6)
    }

    @Test
    fun parsePluginsListAndActions() {
        val raw = """
            {
              "ok": true,
              "available": true,
              "hmrEnabled": false,
              "pnpmAvailable": false,
              "profile": "acp",
              "bundles": [
                {
                  "name": "@deepseek-ai/dsh-base",
                  "displayName": "dsh-base",
                  "version": "0.2.2-rc.5",
                  "availability": "installation",
                  "official": false,
                  "optional": false,
                  "enabled": true,
                  "installed": false,
                  "removable": false,
                  "readOnlyReason": "management-required",
                  "pluginCount": 12
                },
                {
                  "name": "@deepseek-ai/dsh-experimental-auto-review",
                  "displayName": "自动代码审查",
                  "description": "自动审查变更",
                  "version": "0.2.2-rc.5",
                  "availability": "installation",
                  "official": true,
                  "optional": true,
                  "enabled": false,
                  "installed": false,
                  "removable": false,
                  "pluginCount": 1
                },
                {
                  "name": "@deepseek-ai/dsh-subagent-codex",
                  "displayName": "Codex Subagent",
                  "availability": "missing",
                  "official": true,
                  "optional": false,
                  "enabled": false,
                  "installed": false,
                  "removable": false
                }
              ]
            }
        """.trimIndent()

        val list = BridgePayloadParsers.parsePluginsList(raw)
        assertTrue(list.ok)
        assertTrue(list.available)
        assertFalse(list.hmrEnabled)
        assertFalse(list.pnpmAvailable)
        assertEquals(3, list.bundles.size)
        assertTrue(list.bundles[0].isProtected)
        assertTrue(list.bundles[1].isShippedBuiltIn)
        assertFalse(list.bundles[1].isProtected)
        assertTrue(list.bundles[2].isOnDemandMissing)

        val act = BridgePayloadParsers.parsePluginAction(
            """{"ok":true,"name":"@deepseek-ai/dsh-experimental-auto-review","application":"restart-required","restartRequired":true}"""
        )
        assertTrue(act.ok)
        assertTrue(act.restartRequired)
        assertFalse(act.missingPnpm)
    }
}
