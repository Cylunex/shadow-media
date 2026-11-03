@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.shadow.media.reading

import android.content.SharedPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

internal class ReaderSettingsStore(private val preferences: SharedPreferences, private val defaults: ReaderSettingsStore? = null) {
    fun load(): ReaderSettings = with(preferences) {
        val base = defaults?.load()
        // Migrate the original reader's four settings without losing the user's choices.
        ReaderSettings(
            fontSize = getFloat("font", base?.fontSize ?: 115f), lineHeight = getFloat("line", base?.lineHeight ?: 1.7f),
            paragraphSpacing = getFloat("paragraph", base?.paragraphSpacing ?: 0.6f), letterSpacing = getFloat("letter", base?.letterSpacing ?: 0f),
            margins = getFloat("margins", base?.margins ?: 1f), font = enumValue("fontFamily", base?.font ?: ReaderFont.SERIF),
            bold = getBoolean("bold", base?.bold ?: false), indent = getBoolean("indent", base?.indent ?: true),
            justify = getBoolean("justify", base?.justify ?: true), comfortableWidth = getBoolean("comfortableWidth", base?.comfortableWidth ?: true),
            showProgress = getBoolean("showProgress", base?.showProgress ?: true), paperTexture = getFloat("paperTexture", base?.paperTexture ?: .65f),
            lastDayPaper = enumValue("lastDayPaper", base?.lastDayPaper ?: ReaderPaper.PARCHMENT),
            paper = enumValue("paper", base?.paper ?: when (getString("theme", null)) {
                "DARK" -> ReaderPaper.NIGHT; "LIGHT" -> ReaderPaper.WHITE; else -> ReaderPaper.PARCHMENT
            }),
            pageMode = enumValue("pageMode", base?.pageMode ?: if (getBoolean("scroll", false)) ReaderPageMode.SCROLL else ReaderPageMode.SLIDE),
            fullScreen = getBoolean("fullScreen", base?.fullScreen ?: true), tapToTurn = getBoolean("tapToTurn", base?.tapToTurn ?: true),
            volumeToTurn = getBoolean("volumeToTurn", base?.volumeToTurn ?: false), brightnessGesture = getBoolean("brightnessGesture", base?.brightnessGesture ?: true),
            pinchToResize = getBoolean("pinchToResize", base?.pinchToResize ?: true), keepAwake = getBoolean("keepAwake", base?.keepAwake ?: true),
            brightness = getFloat("brightness", base?.brightness ?: -1f), verticalMargins = getFloat("verticalMargins", base?.verticalMargins ?: 1f),
            indentSize = getInt("indentSize", base?.indentSize ?: 2), showChapterTitle = getBoolean("showChapterTitle", base?.showChapterTitle ?: true),
            titleScale = getFloat("titleScale", base?.titleScale ?: 1.2f), titleSpacing = getFloat("titleSpacing", base?.titleSpacing ?: .85f),
            titleAlignment = enumValue("titleAlignment", base?.titleAlignment ?: ReaderTitleAlignment.START),
            customFontId = getString("customFontId", base?.customFontId ?: "").orEmpty(), customFontName = getString("customFontName", base?.customFontName ?: "").orEmpty(),
            haptics = getBoolean("haptics", base?.haptics ?: true),
            leftHanded = getBoolean("leftHanded", base?.leftHanded ?: false), publisherStyles = getBoolean("publisherStyles", base?.publisherStyles ?: false)
        ).normalized()
    }

    fun save(value: ReaderSettings) = with(value.normalized()) {
        preferences.edit().putFloat("font", fontSize).putFloat("line", lineHeight)
            .putFloat("paragraph", paragraphSpacing).putFloat("letter", letterSpacing).putFloat("margins", margins)
            .putString("fontFamily", font.name).putBoolean("bold", bold).putBoolean("indent", indent)
            .putBoolean("justify", justify).putBoolean("comfortableWidth", comfortableWidth).putBoolean("showProgress", showProgress)
            .putFloat("paperTexture", paperTexture).putString("lastDayPaper", lastDayPaper.name)
            .putString("paper", paper.name).putString("pageMode", pageMode.name).putBoolean("fullScreen", fullScreen)
            .putBoolean("tapToTurn", tapToTurn).putBoolean("volumeToTurn", volumeToTurn)
            .putBoolean("brightnessGesture", brightnessGesture).putBoolean("pinchToResize", pinchToResize)
            .putBoolean("keepAwake", keepAwake).putFloat("brightness", brightness)
            .putFloat("verticalMargins", verticalMargins).putInt("indentSize", indentSize)
            .putBoolean("showChapterTitle", showChapterTitle).putFloat("titleScale", titleScale).putFloat("titleSpacing", titleSpacing)
            .putString("titleAlignment", titleAlignment.name).putString("customFontId", customFontId).putString("customFontName", customFontName)
            .putBoolean("leftHanded", leftHanded).putBoolean("publisherStyles", publisherStyles)
            .putBoolean("haptics", haptics).apply()
        defaults?.let { parent ->
            val baseline = parent.load()
            val baseValues = settingsValues(baseline)
            val deviceKeys = setOf("fullScreen", "tapToTurn", "volumeToTurn", "brightnessGesture", "pinchToResize", "keepAwake", "brightness", "leftHanded", "haptics")
            val device = parent.preferences.edit()
            for ((key, current) in settingsValues(this)) if (key in deviceKeys) {
                when (current) { is Boolean -> device.putBoolean(key, current); is Float -> device.putFloat(key, current) }
            }
            device.apply()
            val edit = preferences.edit()
            for (key in deviceKeys) edit.remove(key)
            for ((key, current) in settingsValues(this)) if (baseValues[key] == current) edit.remove(key)
            edit.apply()
        }
    }
    fun followDefaults() { if (defaults != null) preferences.edit().clear().apply() }
    fun makeDefault(value: ReaderSettings) { (defaults ?: this).save(value); followDefaults() }
    private fun settingsValues(value: ReaderSettings): Map<String, Any> = with(value) {
        mapOf(
            "font" to fontSize,
            "line" to lineHeight,
            "paragraph" to paragraphSpacing,
            "letter" to letterSpacing,
            "margins" to margins,
            "fontFamily" to font.name,
            "bold" to bold,
            "indent" to indent,
            "justify" to justify,
            "comfortableWidth" to comfortableWidth,
            "showProgress" to showProgress,
            "paperTexture" to paperTexture,
            "lastDayPaper" to lastDayPaper.name,
            "paper" to paper.name,
            "pageMode" to pageMode.name,
            "fullScreen" to fullScreen,
            "tapToTurn" to tapToTurn,
            "volumeToTurn" to volumeToTurn,
            "brightnessGesture" to brightnessGesture,
            "pinchToResize" to pinchToResize,
            "keepAwake" to keepAwake,
            "brightness" to brightness,
            "verticalMargins" to verticalMargins,
            "indentSize" to indentSize,
            "showChapterTitle" to showChapterTitle,
            "titleScale" to titleScale,
            "titleSpacing" to titleSpacing,
            "titleAlignment" to titleAlignment.name,
            "customFontId" to customFontId,
            "customFontName" to customFontName,
            "leftHanded" to leftHanded,
            "publisherStyles" to publisherStyles,
            "haptics" to haptics
        )
    }
}

private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(key: String, fallback: T): T =
    runCatching { enumValueOf<T>(getString(key, null).orEmpty()) }.getOrDefault(fallback)

internal fun ReaderSettings.epubPreferences() = with(normalized()) {
    // Readium 3 uses multipliers: 1.15 = 115%, and 1.75 * normal CSS weight 400 = 700.
    // Keep the user's stored percent/bold choices; convert only at the engine boundary.
    if (publisherStyles) EpubPreferences(publisherStyles = true, scroll = pageMode == ReaderPageMode.SCROLL)
    else EpubPreferences(
        fontSize = fontSize / 100.0, lineHeight = lineHeight.toDouble(),
        paragraphSpacing = paragraphSpacing.toDouble(), letterSpacing = letterSpacing.toDouble(),
        pageMargins = margins.toDouble(), paragraphIndent = if (indent) indentSize.toDouble() else 0.0,
        fontFamily = FontFamily(font.css), fontWeight = if (bold) 1.75 else 1.0,
        theme = if (paper == ReaderPaper.NIGHT) Theme.DARK else Theme.LIGHT,
        backgroundColor = Color(paper.background), textColor = Color(paper.foreground),
        publisherStyles = publisherStyles, textAlign = if (justify) TextAlign.JUSTIFY else TextAlign.START, scroll = pageMode == ReaderPageMode.SCROLL
    )
}

/** The same bundled paper as the native reader. A color overlay controls grain without changing layout. */
internal fun readerPaperScript(value: ReaderSettings, textureData: String): String {
    val style = value.normalized()
    val paper = style.paper
    val veil = "rgba(${android.graphics.Color.red(paper.background)},${android.graphics.Color.green(paper.background)},${android.graphics.Color.blue(paper.background)},${1f - style.paperTexture})"
    if (style.publisherStyles) return "document.getElementById('shadow-reader-paper')?.remove();"
    val texture = if (paper.textured && style.paperTexture > 0f && textureData.isNotEmpty())
        "linear-gradient($veil,$veil),url(\"$textureData\")" else "none"
    val blend = if (paper == ReaderPaper.DEEP_PARCHMENT) "normal,multiply" else "normal"
    val background = "#%06X".format(paper.background and 0xFFFFFF)
    return """
        (() => {
          let style = document.getElementById('shadow-reader-paper');
          if (!style) { style = document.createElement('style'); style.id = 'shadow-reader-paper'; document.head.appendChild(style); }
          style.textContent = ${org.json.JSONObject.quote("html { background-color: $background !important; background-blend-mode: $blend !important; background-image: $texture !important; background-size: cover !important; background-position: center !important; background-repeat: no-repeat !important; background-attachment: fixed !important; } body { background-color: transparent !important; }")};
        })();
    """.trimIndent()
}
