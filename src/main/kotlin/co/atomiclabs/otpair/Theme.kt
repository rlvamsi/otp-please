package co.atomiclabs.otpair

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ───────────────────────── Colour ─────────────────────────

/** Decoration only: dots, dashes, tints. Never behind text. */
internal val Coral = Color(0xFFFF5A66)

/** The one primary action on a screen, and the logo. White text on it passes contrast. */
internal val Brand = Brush.horizontalGradient(listOf(Color(0xFFE82E54), Color(0xFFD61C66)))

internal class Palette(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val surface: Color,
    val blush: Color,
    val ink: Color,
    val soft: Color,
    val faint: Color,
    val line: Color,
    val border: Color,
    val danger: Color,
    val good: Color,
)

private val Light = Palette(
    dark = false,
    bg = Color.White, card = Color.White, surface = Color(0xFFF7F7F7), blush = Color(0xFFFFF1EE),
    ink = Color(0xFF222222), soft = Color(0xFF6A6A6A), faint = Color(0xFFB0B0B0),
    line = Color(0xFFEBEBEB), border = Color(0xFFDDDDDD),
    danger = Color(0xFFC13515), good = Color(0xFF008A05),
)

private val Dark = Palette(
    dark = true,
    bg = Color(0xFF111111), card = Color(0xFF1E1E1E), surface = Color(0xFF1B1B1B), blush = Color(0xFF261A1C),
    ink = Color(0xFFF5F5F5), soft = Color(0xFFB0B0B0), faint = Color(0xFF6A6A6A),
    line = Color(0xFF2A2A2A), border = Color(0xFF3D3D3D),
    danger = Color(0xFFFF8A80), good = Color(0xFF55C95F),
)

private val LocalPalette = staticCompositionLocalOf { Light }

internal val look: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

// ───────────────────────── Type ─────────────────────────

private val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_bold, FontWeight.Bold),
)

private val Base = TextStyle(
    fontFamily = DmSans,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Two weights, seven sizes. Big type is tracked in; small caps are tracked out. */
internal object Type {
    val display = Base.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.1).sp)
    val title = Base.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
    val heading = Base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val body = Base.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.Bold)
    val small = Base.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val smallStrong = small.copy(fontWeight = FontWeight.Bold)
    val eyebrow = Base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
    val button = Base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp)
}

@Composable
internal fun T(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = look.ink,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) = Text(text, modifier, color = color, style = style, textAlign = align, maxLines = maxLines, minLines = minLines, overflow = TextOverflow.Ellipsis)

@Composable
internal fun OtpairTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) Dark else Light
    val scheme = with(palette) {
        if (dark) {
            darkColorScheme(primary = ink, onPrimary = bg, background = bg, onBackground = ink, surface = card, onSurface = ink, outlineVariant = line)
        } else {
            lightColorScheme(primary = ink, onPrimary = bg, background = bg, onBackground = ink, surface = card, onSurface = ink, outlineVariant = line)
        }
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme) { ProvideTextStyle(Type.body, content) }
    }
}

// ───────────────────────── Icons ─────────────────────────

/** Line icons on a 24 grid, 1.8 stroke, round joins. Shapes follow the Lucide set (ISC). */
private fun glyph(vararg paths: String) =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        paths.forEach {
            addPath(
                addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

internal object Glyph {
    val Back = glyph("M15 18l-6-6 6-6")
    val Forward = glyph("M9 18l6-6-6-6")
    val Close = glyph("M18 6L6 18", "M6 6l12 12")
    val Check = glyph("M20 6L9 17l-5-5")
    val Copy = glyph(
        "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z",
        "M4 16a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2",
    )
    val Lock = glyph("M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")
    val Message = glyph("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
    val Phone = glyph("M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 18h.01")
    val Unlink = glyph("M9 17H7A5 5 0 0 1 7 7", "M15 7h2a5 5 0 0 1 4 8", "M8 12h4", "M2 2l20 20")
    val Scan = glyph(
        "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2",
        "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", "M7 12h10",
    )
    val Qr = glyph(
        "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
        "M14 14h3v3", "M21 14v.01", "M14 21v-1", "M21 21h-4v-3", "M21 17.5v.01",
    )
    val Settings = glyph(
        "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3",
        "M14 2v4", "M8 10v4", "M16 18v4",
    )
    val Bolt = glyph("M13 2L3 14h9l-1 8 10-12h-9l1-8z")
    val Alert = glyph("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z", "M12 8v4", "M12 16h.01")
    val Bell = glyph("M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val Finger = glyph(
        "M12 10a2 2 0 0 0-2 2c0 1.02-.1 2.51-.26 4", "M14 13.12c0 2.38 0 6.38-1 8.88", "M17.29 21.02c.12-.6.43-2.3.5-3.02",
        "M2 12a10 10 0 0 1 18-6", "M2 16h.01", "M21.8 16c.2-2 .131-5.354 0-6", "M5 19.5C5.5 18 6 15 6 12a6 6 0 0 1 .34-2",
        "M8.65 22c.21-.66.45-1.32.57-2", "M9 6.8a6 6 0 0 1 9 5.2v2",
    )
    val Camera = glyph(
        "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
        "M12 10a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
    )
}

// ───────────────────────── Controls ─────────────────────────

/** Everything tappable gives the same answer to a finger: it sinks a little and springs back. */
@Composable
internal fun Tappable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    enabled: Boolean = true,
    color: Color = Color.Transparent,
    brush: Brush? = null,
    border: BorderStroke? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 700f), label = "press")
    Box(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .then(modifier)
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(color))
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(source, LocalIndication.current, enabled, role = Role.Button, onClick = onClick),
        Alignment.Center,
        content = content,
    )
}

@Composable
private fun ButtonBase(
    text: String,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    content: Color,
    onClick: () -> Unit,
    color: Color = Color.Transparent,
    brush: Brush? = null,
    border: BorderStroke? = null,
) {
    Tappable(
        onClick, modifier.height(56.dp), enabled = enabled,
        color = if (enabled) color else look.border, brush = brush.takeIf { enabled }, border = border.takeIf { enabled },
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            val tint = if (enabled) content else look.bg
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp), tint)
                Spacer(Modifier.width(10.dp))
            }
            T(text, Type.button, color = tint, maxLines = 1)
        }
    }
}

/** One per screen. */
@Composable
internal fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) =
    ButtonBase(text, modifier, icon, enabled, Color.White, onClick, brush = Brand)

@Composable
internal fun DarkButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) =
    ButtonBase(text, modifier, icon, true, look.bg, onClick, color = look.ink)

@Composable
internal fun OutlineButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) =
    ButtonBase(text, modifier, icon, true, look.ink, onClick, border = BorderStroke(1.dp, look.ink))

/** An underlined text action. The touch area is taller than the words. */
@Composable
internal fun TextLink(text: String, modifier: Modifier = Modifier, color: Color = look.ink, onClick: () -> Unit) =
    Tappable(onClick, modifier.heightIn(min = 48.dp), RoundedCornerShape(10.dp)) {
        T(text, Type.bodyStrong.copy(textDecoration = TextDecoration.Underline), Modifier.padding(horizontal = 8.dp), color)
    }

/** A round icon button: 40 to look at, 48 to touch. */
@Composable
internal fun CircleButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onDark: Boolean = false, onClick: () -> Unit) =
    Tappable(onClick, modifier.size(48.dp), CircleShape) {
        Box(
            Modifier.size(40.dp)
                .background(if (onDark) Color(0x66000000) else look.bg, CircleShape)
                .border(1.dp, if (onDark) Color(0x40FFFFFF) else look.border, CircleShape),
            Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(20.dp), if (onDark) Color.White else look.ink)
        }
    }

/** On is ink, not coral: coral is saved for the screen's one action. */
@Composable
internal fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (on) look.ink else look.faint, label = "track")
    val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.7f, stiffness = 500f), label = "thumb")
    Box(modifier.size(52.dp, 32.dp).background(track, CircleShape).padding(2.dp)) {
        Box(Modifier.offset(x = x).size(28.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape), Alignment.Center) {
            if (on) Icon(Glyph.Check, null, Modifier.size(14.dp), Color(0xFF222222))
        }
    }
}

/**
 * A sheet that slides up for a decision. [content] is handed a close function that slides the
 * sheet away first and then runs what was decided.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = look.card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp).size(36.dp, 4.dp).background(look.border, CircleShape)) },
    ) {
        Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp)) {
            content { then ->
                scope.launch { state.hide() }.invokeOnCompletion {
                    onDismiss()
                    then()
                }
            }
        }
    }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(look.line))

/**
 * The mark: two linked rings on the brand colour, matching the launcher icon.
 * [join] below 1 pulls the rings apart, for the moment two phones pair.
 */
@Composable
internal fun Logo(size: Dp, modifier: Modifier = Modifier, join: Float = 1f) {
    Canvas(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brand)) {
        val r = this.size.width * 0.19f
        val ring = Stroke(this.size.width * 0.075f)
        val apart = r * (0.57f + (1f - join) * 0.75f)
        drawCircle(Color.White, r, center.copy(x = center.x - apart), style = ring)
        drawCircle(Color.White, r, center.copy(x = center.x + apart), style = ring)
    }
}
