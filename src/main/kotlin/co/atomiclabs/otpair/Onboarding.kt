package co.atomiclabs.otpair

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGES = 3

private val Titles = listOf("Never wait for\nan OTP again.", "Only the two of\nyou can read it.", "Pair your phones\nonce.")
private val Bodies = listOf(
    "When a bank code lands on your partner’s phone, you get it on yours with one tap.",
    "A code only leaves the phone when your partner asks, and it travels locked. No account, no sign-up.",
    "One phone shows a code and the other scans it. That’s the whole setup.",
)

/** Three pages: what it does, why it's safe, how to start. The last page is where pairing begins. */
@Composable
internal fun Onboarding(skipIntro: Boolean, onShow: () -> Unit, onScan: () -> Unit) {
    val pager = rememberPagerState(initialPage = if (skipIntro) PAGES - 1 else 0) { PAGES }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1
    fun go(page: Int) {
        scope.launch { pager.animateScrollToPage(page) }
    }
    BackHandler(pager.currentPage > 0 && !skipIntro) { go(pager.currentPage - 1) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(30.dp)
            Spacer(Modifier.width(10.dp))
            T("OTP Please", Type.heading)
            Spacer(Modifier.weight(1f))
            Dots(pager.currentPage)
        }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            Page(page, playing = pager.settledPage == page)
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            PrimaryButton(if (last) "Show my code" else "Continue", Modifier.fillMaxWidth(), icon = Glyph.Qr.takeIf { last }) {
                if (last) onShow() else go(pager.currentPage + 1)
            }
            Spacer(Modifier.height(12.dp))
            Crossfade(last, label = "second action") { pairing ->
                Box(Modifier.fillMaxWidth().height(56.dp), Alignment.Center) {
                    if (pairing) {
                        OutlineButton("Scan partner’s code", Modifier.fillMaxWidth(), icon = Glyph.Scan, onClick = onScan)
                    } else {
                        TextLink("Skip") { go(PAGES - 1) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Dots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PAGES) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 6.dp, label = "dot width")
            val color by animateColorAsState(if (i == current) look.ink else look.border, label = "dot colour")
            Box(Modifier.size(width, 6.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun Page(page: Int, playing: Boolean) {
    val context = LocalContext.current
    // People who turned animations off see each picture in its finished pose.
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val moving = playing && !still

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(look.blush).clearAndSetSemantics {},
        ) {
            when (page) {
                0 -> Art(300.dp, 390.dp, bleed = true) { Handoff(loop(moving, 4200, rest = 0.8f)) }
                1 -> Art(280.dp, 316.dp, bleed = false) { Sealed(moving) }
                else -> Art(300.dp, 390.dp, bleed = true) { Pairing(loop(moving, 3800, rest = 0.8f)) }
            }
        }
        Spacer(Modifier.height(28.dp))
        T(Titles[page], Type.display)
        Spacer(Modifier.height(10.dp))
        T(Bodies[page], Type.body, color = look.soft, minLines = 3)
        Spacer(Modifier.height(20.dp))
    }
}

// ───────────────────────── Pictures ─────────────────────────

/**
 * A picture drawn at a fixed size and scaled to fit its panel, so it looks the same on every
 * phone. With [bleed] it stands on the panel's bottom edge and is cut off there.
 */
@Composable
private fun Art(width: Dp, height: Dp, bleed: Boolean, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = if (bleed) 0.dp else 20.dp)) {
        val scale = minOf(1.25f, maxWidth / width, maxHeight / height)
        val anchor = if (bleed) Alignment.BottomCenter else Alignment.Center
        Box(
            Modifier.fillMaxSize().wrapContentSize(anchor, unbounded = true).size(width, height).graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, if (bleed) 1f else 0.5f)
            },
            content = content,
        )
    }
}

@Composable
private fun loop(moving: Boolean, millis: Int, rest: Float): Float {
    if (!moving) return rest
    val t by rememberInfiniteTransition(label = "picture")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(millis, easing = LinearEasing)), label = "time")
    return t
}

/** How far through the slice [from]..[to] of the loop we are, eased. */
private fun Float.part(from: Float, to: Float) = FastOutSlowInEasing.transform(((this - from) / (to - from)).coerceIn(0f, 1f))

private fun art(size: Int, bold: Boolean = false) =
    Type.small.copy(fontSize = size.sp, lineHeight = (size * 1.35f).sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

private val ArtLabel = Type.eyebrow.copy(fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 0.9.sp)

@Composable
private fun PhoneCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    Column(
        modifier.shadow(18.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x30000000))
            .background(look.card, shape).border(1.dp, look.line, shape).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(38.dp, 5.dp).background(look.border, CircleShape))
        Spacer(Modifier.height(18.dp))
        content()
    }
}

@Composable
private fun Chip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.shadow(12.dp, shape, ambientColor = Color(0x12000000), spotColor = Color(0x28000000))
            .background(look.card, shape).border(1.dp, look.line, shape).padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}

@Composable
private fun CodeDots(color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(6) { i ->
            if (i == 3) Spacer(Modifier.width(8.dp))
            Box(Modifier.padding(horizontal = 4.dp).size(9.dp).background(color, CircleShape))
        }
    }
}

/** Grey lines standing in for the rest of a screen. Each number is a share of the full width. */
@Composable
private fun Bars(vararg widths: Float) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        widths.forEach { Box(Modifier.fillMaxWidth(it).height(9.dp).background(look.line, CircleShape)) }
    }
}

/** A message lands on one phone; its code hops to the other. */
@Composable
private fun BoxScope.Handoff(t: Float) {
    val landed = t.part(0.03f, 0.16f) * (1f - t.part(0.94f, 1f))
    val hop = t.part(0.24f, 0.46f)
    val shown = t.part(0.46f, 0.56f) * (1f - t.part(0.94f, 1f))

    PhoneCard(Modifier.align(Alignment.BottomEnd).offset(x = (-16).dp).size(204.dp, 300.dp)) {
        T("YOUR PHONE", ArtLabel, color = look.soft)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.height(44.dp), Alignment.Center) {
            CodeDots(look.border, Modifier.graphicsLayer { alpha = 1f - shown })
            T("482 913", art(31, bold = true).copy(letterSpacing = 0.5.sp), Modifier.graphicsLayer {
                alpha = shown
                translationY = (1f - shown) * 10.dp.toPx()
            })
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(42.dp).background(Brand, RoundedCornerShape(12.dp)), Alignment.Center) {
            T("Copy code", art(13, bold = true), color = Color.White)
        }
        Spacer(Modifier.height(22.dp))
        Bars(1f, 0.62f)
    }

    Chip(
        Modifier.align(Alignment.TopStart).offset(x = 2.dp, y = 16.dp).width(226.dp).graphicsLayer {
            alpha = landed
            translationY = (1f - landed) * -14.dp.toPx()
            rotationZ = -4f
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(look.blush, CircleShape), Alignment.Center) {
                Icon(Glyph.Message, null, Modifier.size(18.dp), Coral)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                T("PARTNER’S PHONE", ArtLabel, color = look.soft)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildAnnotatedString {
                        append("Your OTP is ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("482913") }
                    },
                    color = look.ink, style = art(14),
                )
            }
        }
    }

    Canvas(Modifier.matchParentSize()) {
        val from = Offset(234.dp.toPx(), 50.dp.toPx())
        val bend = Offset(272.dp.toPx(), 54.dp.toPx())
        val to = Offset(246.dp.toPx(), 86.dp.toPx())
        val path = Path().apply {
            moveTo(from.x, from.y)
            quadraticTo(bend.x, bend.y, to.x, to.y)
        }
        drawPath(
            path, Coral.copy(alpha = 0.55f * landed),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 7.dp.toPx()))),
        )
        if (hop > 0f && hop < 1f) {
            val u = 1f - hop
            val at = Offset(
                u * u * from.x + 2 * u * hop * bend.x + hop * hop * to.x,
                u * u * from.y + 2 * u * hop * bend.y + hop * hop * to.y,
            )
            drawCircle(Color.White, 8.dp.toPx(), at)
            drawCircle(Coral, 5.5.dp.toPx(), at)
        }
    }
}

private const val SCRAMBLE = "abcdefghkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789+/"

/** The same code three times: readable at both ends, gibberish in between. */
@Composable
private fun BoxScope.Sealed(moving: Boolean) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(moving) {
        while (moving) {
            delay(140)
            tick++
        }
    }
    val gibberish = remember(tick) {
        buildString {
            repeat(15) {
                if (it > 0 && it % 5 == 0) append(' ')
                append(SCRAMBLE.random())
            }
        }
    }

    Column(Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Chip(Modifier.fillMaxWidth()) {
            Column {
                T("ON YOUR PARTNER’S PHONE", ArtLabel, color = look.soft)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildAnnotatedString {
                        append("Your OTP is ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("482913") }
                    },
                    color = look.ink, style = art(16),
                )
            }
        }
        Dashes()
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF222222), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(Brand, CircleShape), Alignment.Center) {
                Icon(Glyph.Lock, null, Modifier.size(19.dp), Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                T("ON THE WAY", ArtLabel, color = Color(0x99FFFFFF))
                Spacer(Modifier.height(4.dp))
                T(gibberish, art(15).copy(fontFamily = FontFamily.Monospace), color = Color.White, maxLines = 1)
            }
        }
        Dashes()
        Chip(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("ON YOUR PHONE", ArtLabel, color = look.soft)
                    Spacer(Modifier.height(2.dp))
                    T("482 913", art(26, bold = true).copy(letterSpacing = 0.5.sp))
                }
                Box(Modifier.size(28.dp).background(look.good, CircleShape), Alignment.Center) {
                    Icon(Glyph.Check, null, Modifier.size(15.dp), Color.White)
                }
            }
        }
    }
}

@Composable
private fun Dashes() {
    Canvas(Modifier.size(2.dp, 26.dp)) {
        drawLine(
            Coral, Offset(center.x, 5.dp.toPx()), Offset(center.x, size.height - 3.dp.toPx()), 2.dp.toPx(), StrokeCap.Round,
            PathEffect.dashPathEffect(floatArrayOf(1f, 6.dp.toPx())),
        )
    }
}

/** One phone shows a code, the other scans it, and the two rings link. */
@Composable
private fun BoxScope.Pairing(t: Float) {
    val sweep = t.part(0.08f, 0.5f)
    val linked = t.part(0.5f, 0.64f) * (1f - t.part(0.95f, 1f))
    val ink = look.ink
    val n = 17
    // Looks like a QR code; isn't one. The same pattern every time so the picture never flickers.
    val cells = remember { java.util.Random(11).let { r -> BooleanArray(n * n) { r.nextBoolean() } } }

    PhoneCard(Modifier.align(Alignment.BottomStart).offset(x = 16.dp).size(128.dp, 250.dp)) {
        Canvas(Modifier.size(96.dp)) {
            val c = size.width / n
            fun eye(ox: Int, oy: Int) {
                drawRoundRect(ink, Offset((ox + 0.5f) * c, (oy + 0.5f) * c), Size(4 * c, 4 * c), CornerRadius(c * 1.3f), Stroke(c))
                drawRoundRect(ink, Offset((ox + 1.7f) * c, (oy + 1.7f) * c), Size(1.6f * c, 1.6f * c), CornerRadius(c * 0.5f))
            }
            eye(0, 0)
            eye(n - 5, 0)
            eye(0, n - 5)
            for (y in 0 until n) for (x in 0 until n) {
                val inEye = (x < 6 && y < 6) || (x >= n - 6 && y < 6) || (x < 6 && y >= n - 6)
                if (!inEye && cells[y * n + x]) {
                    drawRoundRect(ink, Offset((x + 0.1f) * c, (y + 0.1f) * c), Size(c * 0.8f, c * 0.8f), CornerRadius(c * 0.28f))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Bars(1f, 0.6f)
    }

    PhoneCard(Modifier.align(Alignment.BottomEnd).offset(x = (-16).dp).size(128.dp, 206.dp)) {
        Canvas(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF262626))) {
            brackets(Color.White, inset = 16.dp.toPx(), arm = 14.dp.toPx(), radius = 8.dp.toPx(), width = 3.dp.toPx())
            if (sweep > 0f && sweep < 1f) {
                val y = 22.dp.toPx() + (size.height - 44.dp.toPx()) * sweep
                drawLine(Coral, Offset(22.dp.toPx(), y), Offset(size.width - 22.dp.toPx(), y), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        Spacer(Modifier.height(18.dp))
        Bars(1f)
    }

    Logo(
        58.dp,
        Modifier.align(Alignment.TopCenter).offset(y = 44.dp).graphicsLayer {
            val pop = 0.9f + 0.1f * linked
            scaleX = pop
            scaleY = pop
            shadowElevation = 14.dp.toPx()
            shape = RoundedCornerShape(16.dp)
        },
        join = linked,
    )
}
