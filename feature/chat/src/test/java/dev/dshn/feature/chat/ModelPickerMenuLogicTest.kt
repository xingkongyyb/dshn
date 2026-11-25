package dev.dshn.feature.chat

import dev.dshn.app.acp.AcpConfigChoice
import dev.dshn.app.acp.AcpConfigOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerMenuLogicTest {

    private val model = AcpConfigOption(
        id = "model",
        name = "Model",
        category = "model",
        currentValue = "deepseek/v4.1-flash",
        choices = listOf(
            AcpConfigChoice("deepseek/v4.1-flash", "DeepSeek-V4.1-Flash", group = "DeepSeek"),
            AcpConfigChoice("deepseek/v4-pro", "DeepSeek-V4-Pro", "Stronger agentic coding", group = "DeepSeek")
        )
    )
    private val effort = AcpConfigOption(
        id = "reasoning_effort",
        name = "Reasoning effort",
        category = "thought_level",
        currentValue = "high",
        choices = listOf(
            AcpConfigChoice("off", "Off", "Use for simple tasks that do not need reasoning."),
            AcpConfigChoice("high", "High", "The default balance for most tasks.")
        )
    )
    private val mode = AcpConfigOption(
        id = "mode",
        name = "Mode",
        currentValue = "plan",
        choices = listOf(AcpConfigChoice("plan", "Plan"), AcpConfigChoice("act", "Act"))
    )

    @Test
    fun effortIsMergedIntoModelPickerAndOthersStaySeparate() {
        val groups = groupComposerConfigOptions(listOf(mode, effort, model))
        assertSame(model, groups.model)
        assertSame(effort, groups.effort)
        assertEquals(listOf(mode), groups.others)
    }

    @Test
    fun loneEffortWithoutModelKeepsItsOwnChip() {
        val groups = groupComposerConfigOptions(listOf(effort))
        assertNull(groups.model)
        assertNull(groups.effort)
        assertEquals(listOf(effort), groups.others)
    }

    @Test
    fun optionsWithoutChoicesAreHidden() {
        val emptyEffort = effort.copy(choices = emptyList())
        val groups = groupComposerConfigOptions(listOf(model, emptyEffort))
        assertSame(model, groups.model)
        assertNull(groups.effort)
        assertTrue(groups.others.isEmpty())
    }

    @Test
    fun knownEffortsAreLocalizedAndUnknownOnesFallBack() {
        assertEquals("高" to "适合大多数任务的默认平衡", effortLabel("high", "High", "x", isZh = true))
        assertEquals("最高", effortLabel("MAX", "Max", "", isZh = true).first)
        assertEquals("默认", effortLabel("", "Provider default", "", isZh = true).first)
        // English UI keeps the provider's own wording.
        assertEquals("High" to "provider text", effortLabel("high", "High", "provider text", isZh = false))
        // Unknown ids show whatever the provider sent.
        assertEquals("Turbo" to "fast", effortLabel("turbo", "Turbo", "fast", isZh = true))
        assertEquals("turbo", effortLabel("turbo", "", "", isZh = true).first)
    }

    @Test
    fun menuLayoutAdaptsToScreenAndFontScale() {
        val phone = modelMenuLayout(screenWidthDp = 393, screenHeightDp = 852, fontScale = 1f)
        assertFalse(phone.twoPane)
        assertEquals(300f, phone.listWidth.value, 0.01f)
        assertTrue(modelMenuLayout(800, 1280, 1f).twoPane)
        // Large text on a tablet: panes grow and still fit side by side.
        val bigText = modelMenuLayout(800, 1280, 1.3f)
        assertTrue(bigText.twoPane)
        assertEquals(390f, bigText.listWidth.value, 0.01f)
        // Phone landscape is wide but short: use the two-level layout.
        assertFalse(modelMenuLayout(900, 400, 1f).twoPane)
        // Tiny screen: the menu never gets wider than the screen minus margins.
        assertEquals(296f, modelMenuLayout(320, 640, 1f).listWidth.value, 0.01f)
    }
}
