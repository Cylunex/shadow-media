@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.shadow.media.reading

import android.content.SharedPreferences
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

internal class ReaderSettingsStore(private val preferences: SharedPreferences) {
    fun load(): ReaderSettings = with(preferences) {
        // Migrate the original reader's four settings without losing the user's choices.
        ReaderSettings(
            fontSize = getFloat("font", 115f), lineHeight = getFloat("line", 1.7f),
            paragraphSpacing = getFloat("paragraph", 0.6f), letterSpacing = getFloat("letter", 0f),
            margins = getFloat("margins", 1f), font = enumValue("fontFamily", ReaderFont.SERIF),
            bold = getBoolean("bold", false), indent = getBoolean("indent", true),
            justify = getBoolean("justify", true), comfortableWidth = getBoolean("comfortableWidth", true),
            showProgress = getBoolean("showProgress", true), paperTexture = getFloat("paperTexture", .65f),
            lastDayPaper = enumValue("lastDayPaper", ReaderPaper.PARCHMENT),
            paper = enumValue("paper", when (getString("theme", null)) {
                "DARK" -> ReaderPaper.NIGHT; "LIGHT" -> ReaderPaper.WHITE; else -> ReaderPaper.PARCHMENT
            }),
            pageMode = enumValue("pageMode", if (getBoolean("scroll", false)) ReaderPageMode.SCROLL else ReaderPageMode.SLIDE),
            fullScreen = getBoolean("fullScreen", true), tapToTurn = getBoolean("tapToTurn", true),
            volumeToTurn = getBoolean("volumeToTurn", false), brightnessGesture = getBoolean("brightnessGesture", true),
            pinchToResize = getBoolean("pinchToResize", true), keepAwake = getBoolean("keepAwake", true),
            brightness = getFloat("brightness", -1f)
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
            .putBoolean("keepAwake", keepAwake).putFloat("brightness", brightness).apply()
    }
}

private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(key: String, fallback: T): T =
    runCatching { enumValueOf<T>(getString(key, null).orEmpty()) }.getOrDefault(fallback)

internal fun ReaderSettings.epubPreferences() = with(normalized()) {
    // Readium 3 uses multipliers: 1.15 = 115%, and 1.75 * normal CSS weight 400 = 700.
    // Keep the user's stored percent/bold choices; convert only at the engine boundary.
    EpubPreferences(
        fontSize = fontSize / 100.0, lineHeight = lineHeight.toDouble(),
        paragraphSpacing = paragraphSpacing.toDouble(), letterSpacing = letterSpacing.toDouble(),
        pageMargins = margins.toDouble(), paragraphIndent = if (indent) 2.0 else 0.0,
        fontFamily = FontFamily(font.css), fontWeight = if (bold) 1.75 else 1.0,
        theme = if (paper == ReaderPaper.NIGHT) Theme.DARK else Theme.LIGHT,
        backgroundColor = Color(paper.background), textColor = Color(paper.foreground),
        publisherStyles = false, textAlign = if (justify) TextAlign.JUSTIFY else TextAlign.START, scroll = pageMode == ReaderPageMode.SCROLL
    )
}

/** The same bundled paper as the native reader. A color overlay controls grain without changing layout. */
internal fun readerPaperScript(value: ReaderSettings, textureData: String): String {
    val style = value.normalized()
    val paper = style.paper
    val veil = "rgba(${android.graphics.Color.red(paper.background)},${android.graphics.Color.green(paper.background)},${android.graphics.Color.blue(paper.background)},${1f - style.paperTexture})"
    val texture = if (paper == ReaderPaper.PARCHMENT && style.paperTexture > 0f && textureData.isNotEmpty())
        "linear-gradient($veil,$veil),url(\"$textureData\")" else "none"
    return """
        (() => {
          let style = document.getElementById('shadow-reader-paper');
          if (!style) { style = document.createElement('style'); style.id = 'shadow-reader-paper'; document.head.appendChild(style); }
          style.textContent = ${org.json.JSONObject.quote("html { background-image: $texture !important; background-size: cover !important; background-position: center !important; background-repeat: no-repeat !important; background-attachment: fixed !important; } body { background-color: transparent !important; }")};
        })();
    """.trimIndent()
}
