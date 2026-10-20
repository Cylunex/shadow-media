@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package top.cylunex.shadowmedia.reading

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
        publisherStyles = false, textAlign = TextAlign.JUSTIFY, scroll = pageMode == ReaderPageMode.SCROLL
    )
}

/** Generated locally: no remote texture, font download or image dependency. Does not change pagination. */
internal fun readerPaperScript(paper: ReaderPaper): String {
    val texture = if (paper == ReaderPaper.PARCHMENT) {
        "radial-gradient(ellipse at 50% 45%, transparent 30%, rgba(116,74,24,.12) 100%)," +
            "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='180' height='180'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.65' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Cpath fill='%238b642c' opacity='.12' filter='url(%23n)' d='M0 0h180v180H0z'/%3E%3C/svg%3E\")"
    } else "none"
    return """
        (() => {
          let style = document.getElementById('shadow-reader-paper');
          if (!style) { style = document.createElement('style'); style.id = 'shadow-reader-paper'; document.head.appendChild(style); }
          style.textContent = ${org.json.JSONObject.quote("html { background-image: $texture !important; background-attachment: fixed !important; } body { background-color: transparent !important; }")};
        })();
    """.trimIndent()
}
