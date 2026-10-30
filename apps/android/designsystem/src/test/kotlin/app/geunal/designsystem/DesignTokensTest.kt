package app.geunal.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import app.geunal.designsystem.components.SheetScrim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * Keeps the Compose values equal to docs/design/tokens.json, which the design changes and iOS reads too: a token that
 * moves there fails here until this side follows.
 */
class DesignTokensTest {
    private val tokens: JsonObject = Json.parseToJsonElement(tokensFile().readText()).jsonObject

    @Test
    fun spacesMatch() {
        val spaces = tokens["space"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }
        assertEquals(spaces, listOf(Space.xxs, Space.xs, Space.s, Space.m, Space.l, Space.xl, Space.xxl, Space.xxxl).map { it.value })
    }

    @Test
    fun radiiMatch() {
        assertDp("radius.strip", Radius.strip)
        assertDp("radius.cell", Radius.cell)
        assertDp("radius.input", Radius.input)
        assertDp("radius.card", Radius.card)
        assertDp("radius.sheet", Radius.sheet)
    }

    @Test
    fun componentSizesMatch() {
        assertDp("a11y.minHitTarget", Metrics.minHitTarget)
        assertDp("component.button.primary", Metrics.Button.primary)
        assertDp("component.button.secondary", Metrics.Button.secondary)
        assertDp("component.button.text", Metrics.Button.text)
        assertDp("component.button.smallPill", Metrics.Button.smallPill)
        assertDp("component.button.icon", Metrics.Button.icon)
        assertDp("component.button.sideInset", Metrics.Button.sideInset)
        assertDp("component.chip.suggestion", Metrics.Chip.suggestion)
        assertDp("component.chip.select", Metrics.Chip.select)
        assertDp("component.chip.badge", Metrics.Chip.badge)
        assertDp("component.chip.dot", Metrics.Chip.dot)
        assertDp("component.segment.height", Metrics.Segment.height)
        assertDp("component.segment.padding", Metrics.Segment.padding)
        assertDp("component.checkbox.size", Metrics.Checkbox.size)
        assertDp("component.checkbox.radius", Metrics.Checkbox.radius)
        assertDp("component.row.setting", Metrics.Row.setting)
        assertDp("component.row.twoLine", Metrics.Row.twoLine)
        assertDp("component.row.event", Metrics.Row.event)
        assertDp("component.row.card", Metrics.Row.card)
        assertDp("component.sheet.topRadius", Metrics.Sheet.topRadius)
        assertDp("component.sheet.padding.top", Metrics.Sheet.paddingTop)
        assertDp("component.sheet.padding.side", Metrics.Sheet.paddingSide)
        assertDp("component.sheet.padding.bottom", Metrics.Sheet.paddingBottom)
        assertEquals(number("component.sheet.maxHeightRatio"), Metrics.Sheet.MAX_HEIGHT_RATIO.toDouble(), 1e-6)
        assertDp("sheet.grabber.width", Metrics.Sheet.grabberWidth)
        assertDp("sheet.grabber.height", Metrics.Sheet.grabberHeight)
        assertDp("component.toast.height", Metrics.Toast.height)
        assertDp("component.toast.radius", Metrics.Toast.radius)
        assertDp("component.toast.gapAboveTabBar", Metrics.Toast.gapAboveTabBar)
        assertDp("nav.tabBar.height", Metrics.TabBar.height)
        assertDp("nav.tabBar.sideInset", Metrics.TabBar.sideInset)
        assertDp("nav.tabBar.bottomInset", Metrics.TabBar.bottomInset)
        assertDp("nav.tabBar.radius", Metrics.TabBar.radius)
        assertDp("nav.tabBar.icon", Metrics.TabBar.icon)
        assertDp("nav.nextUpBar.height", Metrics.NextUpBar.height)
        assertDp("nav.nextUpBar.gapAboveTabBar", Metrics.NextUpBar.gapAboveTabBar)
        assertDp("nav.nextUpBar.radius", Metrics.NextUpBar.radius)
        assertDp("nav.nextUpBar.thumb", Metrics.NextUpBar.thumb)
        assertDp("nav.nextUpBar.thumbRadius", Metrics.NextUpBar.thumbRadius)
    }

    @Test
    fun motionMatches() {
        assertEquals(number("motion.press.scale"), Motion.PRESS_SCALE.toDouble(), 1e-6)
        assertEquals(number("motion.press.ms").toInt(), Motion.PRESS_MS)
        assertEquals(number("motion.toast.inMs").toInt(), Motion.TOAST_IN_MS)
        assertEquals(number("motion.toast.outMs").toInt(), Motion.TOAST_OUT_MS)
        assertEquals(number("component.toast.durationMs").toLong(), Motion.TOAST_DURATION_MS)
        assertEquals(number("motion.nextUp.ms").toInt(), Motion.NEXT_UP_MS)
        assertEquals(number("motion.reducedMotion.fadeMs").toInt(), Motion.REDUCED_MOTION_FADE_MS)
    }

    @Test
    fun typeStylesMatch() {
        val styles =
            mapOf(
                "largeTitle" to GeunalType.largeTitle,
                "title1" to GeunalType.title1,
                "title2" to GeunalType.title2,
                "bodyStrong" to GeunalType.bodyStrong,
                "body" to GeunalType.body,
                "secondary" to GeunalType.secondary,
                "caption" to GeunalType.caption,
                "micro" to GeunalType.micro,
            )
        for ((name, style) in styles) assertType(name, style)
    }

    @Test
    fun neutralColorsMatch() {
        for ((mode, colors) in listOf("light" to LightNeutrals, "dark" to DarkNeutrals)) {
            val roles =
                mapOf(
                    "background" to colors.background,
                    "groupedBackground" to colors.groupedBackground,
                    "emptyCell" to colors.emptyCell,
                    "fill" to colors.fill,
                    "separator" to colors.separator,
                    "ink" to colors.ink,
                    "secondaryText" to colors.secondaryText,
                    "tertiaryText" to colors.tertiaryText,
                    "sunday" to colors.sunday,
                    "saturday" to colors.saturday,
                )
            for ((role, color) in roles) assertEquals("$mode.$role", text("color.$mode.$role"), hex(color))
        }
    }

    @Test
    fun brandAndGroupColorsMatch() {
        assertEquals(text("color.brand.grape"), hex(BrandColors.grape))
        assertEquals(text("color.brand.tabText.light"), hex(BrandColors.tabTextLight))
        assertEquals(text("color.brand.tabText.dark"), hex(BrandColors.tabTextDark))
        assertEquals(text("color.brand.androidIndicator"), hex(BrandColors.androidIndicator))
        assertEquals(text("color.brand.androidBar"), hex(BrandColors.androidBar))
        val groups = at("color.group").jsonObject
        assertEquals(groups.keys.toList(), GroupColors.map { it.name })
        for (group in GroupColors) {
            assertEquals(
                group.name,
                groups.getValue(group.name).jsonObject["light"]!!.jsonArray.map {
                    it.jsonPrimitive.content
                },
                group.light.map(::hex),
            )
            assertEquals(
                group.name,
                groups
                    .getValue(group.name)
                    .jsonObject["dark"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content },
                group.dark.map(::hex),
            )
        }
    }

    @Test
    fun sheetScrimMatches() {
        // rgba(10,10,14,0.32)
        val parts = Regex("""rgba\((\d+),(\d+),(\d+),([\d.]+)\)""").matchEntire(text("sheet.scrim"))!!.groupValues
        assertEquals("#0a0a0e", hex(SheetScrim))
        assertEquals((parts[4].toDouble() * 255).roundToInt(), (SheetScrim.alpha * 255).roundToInt())
        assertEquals(listOf(10, 10, 14), parts.subList(1, 4).map { it.toInt() })
    }

    private fun assertType(
        name: String,
        style: TextStyle,
    ) {
        assertEquals("$name size", number("type.$name.size"), style.fontSize.value.toDouble(), 1e-6)
        assertEquals("$name weight", number("type.$name.weight").toInt(), style.fontWeight?.weight)
        assertEquals("$name tracking", number("type.$name.tracking"), style.letterSpacing.value.toDouble(), 1e-6)
        val lineHeight = (at("type.$name") as JsonObject)["lineHeight"]?.jsonPrimitive?.content?.toDouble()
        if (lineHeight != null) {
            assertEquals("$name line height", number("type.$name.size") * lineHeight, style.lineHeight.value.toDouble(), 1e-6)
        }
    }

    private fun assertDp(
        path: String,
        value: Dp,
    ) = assertEquals(path, number(path), value.value.toDouble(), 1e-6)

    private fun at(path: String): JsonElement = path.split('.').fold(tokens as JsonElement) { node, key -> node.jsonObject.getValue(key) }

    private fun number(path: String): Double = at(path).jsonPrimitive.content.toDouble()

    private fun text(path: String): String = at(path).jsonPrimitive.content

    private fun hex(color: Color): String {
        val argb = (color.value shr 32).toLong()
        return "#%06x".format(argb and 0xFFFFFF)
    }

    private fun tokensFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/design/tokens.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("docs/design/tokens.json not found above ${System.getProperty("user.dir")}")
    }
}
