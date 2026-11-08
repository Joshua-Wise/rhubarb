package com.jwise.rexcompanion.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.jwise.rexcompanion.RexApp
import com.jwise.rexcompanion.ThemeMode
import com.jwise.rexcompanion.rex.BlockLive

// Fallback scheme (no dynamic color): a green seed, roles laid out per the M3 tonal model.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3A6A2E), onPrimary = Color.White,
    primaryContainer = Color(0xFFBBF2A6), onPrimaryContainer = Color(0xFF225118),
    secondary = Color(0xFF54634D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E8CC), onSecondaryContainer = Color(0xFF3C4B37),
    tertiary = Color(0xFF386667), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBBEBEC), onTertiaryContainer = Color(0xFF1E4E4F),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF8FAF0), onBackground = Color(0xFF191D16),
    surface = Color(0xFFF8FAF0), onSurface = Color(0xFF191D16),
    surfaceVariant = Color(0xFFDFE4D7), onSurfaceVariant = Color(0xFF43483F),
    outline = Color(0xFF73796E), outlineVariant = Color(0xFFC3C8BC),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F5EA),
    surfaceContainer = Color(0xFFECEFE4), surfaceContainerHigh = Color(0xFFE6E9DF),
    surfaceContainerHighest = Color(0xFFE1E4D9),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA0D58C), onPrimary = Color(0xFF0C3904),
    primaryContainer = Color(0xFF225118), onPrimaryContainer = Color(0xFFBBF2A6),
    secondary = Color(0xFFBBCBB1), onSecondary = Color(0xFF263421),
    secondaryContainer = Color(0xFF3C4B37), onSecondaryContainer = Color(0xFFD7E8CC),
    tertiary = Color(0xFFA0CFD0), onTertiary = Color(0xFF003738),
    tertiaryContainer = Color(0xFF1E4E4F), onTertiaryContainer = Color(0xFFBBEBEC),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF11140F), onBackground = Color(0xFFE1E4D9),
    surface = Color(0xFF11140F), onSurface = Color(0xFFE1E4D9),
    surfaceVariant = Color(0xFF43483F), onSurfaceVariant = Color(0xFFC3C8BC),
    outline = Color(0xFF8D9387), outlineVariant = Color(0xFF43483F),
    surfaceContainerLowest = Color(0xFF0C0F0A), surfaceContainerLow = Color(0xFF191D16),
    surfaceContainer = Color(0xFF1D211A), surfaceContainerHigh = Color(0xFF282B24),
    surfaceContainerHighest = Color(0xFF32362E),
)

@Composable
fun isDark(): Boolean {
    val mode by RexApp.instance.settings.themeMode.collectAsState()
    return when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RexTheme(content: @Composable () -> Unit) {
    val dark = isDark()
    val dynamic by RexApp.instance.settings.dynamicColor.collectAsState()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

// ---- terminal palettes (our own; used when "Terminal colors" follows the app) ------------------

data class TermPalette(val bg: String, val fg: String, val cursor: String, val ansi: List<String>)

val TermLight = TermPalette(
    bg = "F8FAF2", fg = "23281F", cursor = "3A6A2E",
    ansi = listOf(
        "23281F", "B3261E", "3A6A2E", "8A5A00", "2F5DA8", "7E4A9E", "1F6E70", "C5CABE",
        "5B6156", "D2453B", "4E8A3E", "A87410", "4573C4", "9862BA", "2E8B8D", "F2F4EC",
    ),
)
val TermDark = TermPalette(
    bg = "12160F", fg = "DDE3D3", cursor = "A0D58C",
    ansi = listOf(
        "1D221A", "F2766B", "8FCB78", "E2B45C", "86AEF0", "C79BE4", "6FC6C7", "C3C8BC",
        "5B6156", "FF9A90", "A9E592", "F5CB78", "A6C5FF", "DDB6F5", "8EE0E1", "F2F4EC",
    ),
)

fun TermPalette.toJson(): String =
    """{"background":"$bg","foreground":"$fg","cursor":"$cursor","palette":[${ansi.mapIndexed { i, c -> "\"$i=$c\"" }.joinToString(",")}]}"""

// ---- programs --------------------------------------------------------------------------------

/**
 * [drawable] is a brand mark (e.g. Claude) drawn in white on [brandColor]; otherwise [icon] is a
 * Material symbol on the theme's container color.
 */
data class Program(
    val name: String,
    val icon: ImageVector,
    val agent: Boolean = false,
    val drawable: Int? = null,
    val brandColor: Color? = null,
)

/**
 * What's running in a terminal, from the status a program reports (e.g. claude-code) or its
 * foreground process. Claude Code's executable is named after its version (e.g. "2.1.319"), so
 * the path is checked too.
 */
fun programOf(l: BlockLive?): Program {
    val app = l?.statuses?.values?.firstNotNullOfOrNull { it.app }?.lowercase()
    val name = l?.processName?.lowercase().orEmpty()
    val path = l?.processPath?.lowercase().orEmpty()
    fun has(vararg k: String) = k.any { app == it || name == it || path.contains("/$it/") || path.endsWith("/$it") }
    return when {
        has("claude-code", "claude") -> Program("Claude Code", Icons.Default.AutoAwesome, agent = true,
            drawable = com.jwise.rexcompanion.R.drawable.ic_claude, brandColor = Color(0xFFD97757))
        has("codex", "opencode", "aider", "gemini", "pi") -> Program(app ?: name, Icons.Default.SmartToy, agent = true)
        has("vim", "nvim", "vi", "hx", "helix", "nano", "emacs", "micro") -> Program(name, Icons.Default.EditNote)
        has("ssh", "mosh", "mosh-client", "et") -> Program(name, Icons.Default.Lan)
        has("git", "lazygit", "tig", "gitui") -> Program(name, Icons.Default.AccountTree)
        has("node", "npm", "npx", "pnpm", "yarn", "bun", "deno") -> Program(name, Icons.Default.Javascript)
        has("python", "python3", "ipython", "uv", "go", "cargo", "rustc", "make") -> Program(name, Icons.Default.Code)
        has("htop", "btop", "top", "glances") -> Program(name, Icons.Default.Speed)
        has("docker", "kubectl", "k9s", "podman") -> Program(name, Icons.Default.ViewInAr)
        has("less", "more", "man", "bat") -> Program(name, Icons.Default.Description)
        else -> Program(name.ifBlank { "shell" }, Icons.Default.Terminal)
    }
}

/** Status colors that read on both light and dark surfaces. */
@Composable
fun statusColor(state: String?): Color {
    val dark = isDark()
    return when (state) {
        "blocked" -> if (dark) Color(0xFFF5C15C) else Color(0xFF9A6700)
        "error" -> androidx.compose.material3.MaterialTheme.colorScheme.error
        "working" -> if (dark) Color(0xFF86AEF0) else Color(0xFF2F5DA8)
        "done" -> if (dark) Color(0xFF8FCB78) else Color(0xFF3A6A2E)
        else -> androidx.compose.material3.MaterialTheme.colorScheme.outline
    }
}
