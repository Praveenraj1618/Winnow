@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.winnow.gallery.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winnow.gallery.R
import app.winnow.gallery.data.MediaItem
import app.winnow.gallery.data.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/* The look: one dark stage lit by the photos in view, glass controls, everything on springs. */
object W {
    val Bg = Color(0xFF07080C)
    val Ink = Color(0xFFF6F6FA)
    val Muted = Color(0xFFA6ABBD)
    val Glass = Color.White.copy(alpha = .07f)
    val Glass2 = Color.White.copy(alpha = .14f)
    val Line = Color.White.copy(alpha = .14f)
    val Keep = Color(0xFF55F0BA)
    val Trash = Color(0xFFFF6F6F)
    val Paper = Color.White
    val OnPaper = Color(0xFF0A0B10)
    val Dock = Color(0xE614151D)
    val Brand = listOf(Color(0xFF6A3FD0), Color(0xFFD0456F), Color(0xFF1F8F96))
}

private fun display(w: Int) = Font(R.font.bricolage, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
private fun body(w: Int) = Font(R.font.instrument, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Display = FontFamily(display(700), display(800))
val Body = FontFamily(body(400), body(500), body(600), body(700))

object T {
    val Huge = TextStyle(fontFamily = Display, fontWeight = FontWeight(800), fontSize = 60.sp, lineHeight = 58.sp, letterSpacing = (-2.4).sp, color = W.Ink)
    val Title = TextStyle(fontFamily = Display, fontWeight = FontWeight(800), fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-1.4).sp, color = W.Ink)
    val H2 = TextStyle(fontFamily = Display, fontWeight = FontWeight(800), fontSize = 28.sp, lineHeight = 30.sp, letterSpacing = (-.8).sp, color = W.Ink)
    val H3 = TextStyle(fontFamily = Display, fontWeight = FontWeight(700), fontSize = 21.sp, lineHeight = 24.sp, letterSpacing = (-.5).sp, color = W.Ink)
    val Lead = TextStyle(fontFamily = Body, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 23.sp, color = W.Muted)
    val Body1 = TextStyle(fontFamily = Body, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 21.sp, color = W.Ink)
    val Strong = TextStyle(fontFamily = Body, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 21.sp, color = W.Ink)
    val Small = TextStyle(fontFamily = Body, fontWeight = FontWeight(500), fontSize = 12.5.sp, lineHeight = 17.sp, color = W.Muted)
    val Eyebrow = TextStyle(fontFamily = Body, fontWeight = FontWeight(700), fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 2.sp, color = W.Muted)
    val Button = TextStyle(fontFamily = Body, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 18.sp, color = W.Ink)
    val Pill = TextStyle(fontFamily = Body, fontWeight = FontWeight(600), fontSize = 11.5.sp, lineHeight = 14.sp, color = Color.White)
}

fun <V> bouncy() = spring<V>(dampingRatio = .55f, stiffness = 340f)
fun <V> smooth() = spring<V>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 260f)

@Composable
fun Txt(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null) {
    val s = style.let { if (color != Color.Unspecified) it.copy(color = color) else it }.let { if (align != null) it.copy(textAlign = align) else it }
    BasicText(text, modifier, s, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Slow drifting light behind everything, coloured by the photos on screen. */
@Composable
fun Ambient(colors: List<Color>, modifier: Modifier = Modifier) {
    val a by animateColorAsState(colors.getOrElse(0) { W.Brand[0] }, tween(1400), label = "a1")
    val b by animateColorAsState(colors.getOrElse(1) { W.Brand[1] }, tween(1400), label = "a2")
    val c by animateColorAsState(colors.getOrElse(2) { W.Brand[2] }, tween(1400), label = "a3")
    val inf = rememberInfiniteTransition(label = "amb")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    val u by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(33000, easing = LinearEasing), RepeatMode.Reverse), label = "u")
    Canvas(modifier.fillMaxSize()) {
        drawRect(W.Bg)
        val r = size.maxDimension * .72f
        fun orb(col: Color, cx: Float, cy: Float, k: Float) {
            val o = Offset(size.width * cx, size.height * cy)
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = .62f), col.copy(alpha = 0f)), center = o, radius = r * k), radius = r * k, center = o)
        }
        orb(a, -.1f + .45f * t, -.05f + .2f * u, 1f + .2f * t)
        orb(b, 1.1f - .5f * u, .12f + .3f * t, .86f + .2f * u)
        orb(c, .2f + .4f * u, 1.05f - .25f * t, 1.05f)
        drawRect(Brush.verticalGradient(listOf(W.Bg.copy(alpha = .38f), W.Bg.copy(alpha = .88f)), endY = size.height * .48f))
    }
}

/** Shrinks on press with a spring, like a physical key. */
@Composable
fun Modifier.press(onClick: () -> Unit, enabled: Boolean = true, label: String? = null, role: Role = Role.Button, scaleTo: Float = .93f): Modifier {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (down) scaleTo else 1f, bouncy(), label = "press")
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
        .clickable(src, indication = null, enabled = enabled, role = role, onClick = onClick)
}

@Composable
fun Glass(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(26.dp), pad: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(shape).background(W.Glass).border(1.dp, W.Line, shape).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, big: Boolean = false, glyph: Int = -1, tint: Color = Color.Unspecified) {
    val shape = RoundedCornerShape(50)
    val fg = if (primary) W.OnPaper else if (tint != Color.Unspecified) tint else W.Ink
    Row(
        modifier.alpha(if (enabled) 1f else .38f).press(onClick, enabled).clip(shape)
            .background(if (primary) W.Paper else W.Glass).then(if (primary) Modifier else Modifier.border(1.dp, W.Line, shape))
            .padding(horizontal = if (big) 22.dp else 18.dp, vertical = if (big) 18.dp else 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically
    ) {
        if (glyph >= 0) Glyph(glyph, fg, 20.dp)
        Txt(text, if (big) T.Button.copy(fontSize = 17.sp) else T.Button, color = fg, maxLines = 1)
    }
}

@Composable
fun RoundBtn(glyph: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false, enabled: Boolean = true, size: Dp = 46.dp, tint: Color = W.Ink) {
    Box(
        modifier.alpha(if (enabled) 1f else .3f).size(size).press(onClick, enabled, label, scaleTo = .86f).clip(CircleShape)
            .background(if (solid) W.Paper else W.Glass).then(if (solid) Modifier else Modifier.border(1.dp, W.Line, CircleShape)),
        contentAlignment = Alignment.Center
    ) { Glyph(glyph, if (solid) W.OnPaper else tint, 22.dp) }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, bg: Color = Color(0x8C0A0B10), fg: Color = Color.White, glyph: Int = -1) {
    Row(modifier.clip(RoundedCornerShape(50)).background(bg).border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph >= 0) Glyph(glyph, fg, 11.dp)
        Txt(text, T.Pill, color = fg, maxLines = 1)
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (on) W.Paper else W.Glass, label = "chipbg")
    val fg by animateColorAsState(if (on) W.OnPaper else W.Muted, label = "chipfg")
    val shape = RoundedCornerShape(50)
    Box(modifier.press(onClick, scaleTo = .9f).clip(shape).background(bg).then(if (on) Modifier else Modifier.border(1.dp, W.Line, shape)).padding(horizontal = 15.dp, vertical = 9.dp)) {
        Txt(text, T.Button.copy(fontSize = 13.5.sp), color = fg, maxLines = 1)
    }
}

/** A grid picture. Fades in once the phone hands over the thumbnail. */
@Composable
fun Thumb(item: MediaItem, px: Int, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(Thumbs.peek(item, px)?.asImageBitmap(), item.id, px) {
        if (value == null) value = withContext(Dispatchers.IO) { Thumbs.load(ctx, item, px)?.asImageBitmap() }
    }
    val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(260), label = "thumb")
    Box(modifier.background(Color(0xFF15161C))) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize().alpha(a), contentScale = scale)
    }
}

@Composable
fun FileThumb(path: String, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(null, path) { value = withContext(Dispatchers.IO) { Thumbs.file(path)?.asImageBitmap() } }
    Box(modifier.background(Color(0xFF15161C))) { val b = bmp; if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

/** A ring that sweeps to [fraction] on first show. */
@Composable
fun Ring(fraction: Float, modifier: Modifier = Modifier, stroke: Dp = 4.dp, track: Color = W.Glass2, brush: Brush = Brush.linearGradient(listOf(W.Keep, W.Keep)), content: @Composable BoxScope.() -> Unit = {}) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(fraction) { a.animateTo(fraction.coerceIn(0f, 1f), spring(dampingRatio = .8f, stiffness = 60f)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx(); val inset = sw / 2
            val sz = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(sw))
            drawArc(brush, -90f, 360f * a.value, false, Offset(inset, inset), sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Counts up to [target] when it first appears. */
@Composable
fun countUp(target: Float): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target, tween(1100)) }
    return a.value
}

fun bytesParts(b: Long): Pair<String, String> = when {
    b >= 1_000_000_000L -> String.format("%.1f", b / 1e9) to "GB"
    b >= 10_000_000L -> (b / 1_000_000L).toString() to "MB"
    b >= 1_000_000L -> String.format("%.1f", b / 1e6) to "MB"
    b >= 1_000L -> (b / 1_000L).toString() to "KB"
    else -> b.toString() to "B"
}
fun bytesText(b: Long): String = bytesParts(b).let { it.first + " " + it.second }
fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

/* Icons drawn in code, so the app carries no icon library. */
object G { const val GRID = 0; const val SPARK = 1; const val HEART = 2; const val BIN = 3; const val PLUS = 4; const val X = 5; const val CHECK = 6; const val BACK = 7; const val UNDO = 8; const val TUNE = 9; const val PLAY = 10; const val HEART_FULL = 11; const val CAMERA = 12; const val SHARE = 13 }

@Composable
fun Glyph(kind: Int, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { glyph(kind, tint) }
}

private fun DrawScope.glyph(kind: Int, tint: Color) {
    val u = size.minDimension / 24f
    val st = Stroke(1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 1.9f * u, StrokeCap.Round)
    fun path(build: Path.() -> Unit) = Path().apply(build)
    when (kind) {
        G.GRID -> for (x in listOf(3.5f, 13.5f)) for (y in listOf(3.5f, 13.5f))
            drawRoundRect(tint, Offset(x * u, y * u), Size(7 * u, 7 * u), androidx.compose.ui.geometry.CornerRadius(2 * u), style = st)
        G.SPARK -> {
            fun star(cx: Float, cy: Float, r: Float, fill: Boolean) {
                val p = path {
                    for (i in 0 until 8) {
                        val a = Math.PI / 4 * i - Math.PI / 2; val rr = if (i % 2 == 0) r else r * .36f
                        val x = (cx + cos(a).toFloat() * rr) * u; val y = (cy + sin(a).toFloat() * rr) * u
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                if (fill) drawPath(p, tint) else drawPath(p, tint, style = st)
            }
            star(11f, 11f, 8f, false); star(19f, 18.5f, 3.2f, true)
        }
        G.HEART, G.HEART_FULL -> {
            val p = path {
                moveTo(12 * u, 20.5f * u)
                cubicTo(12 * u, 20.5f * u, 4.5f * u, 15.9f * u, 4.5f * u, 10.1f * u)
                cubicTo(4.5f * u, 5.6f * u, 9.8f * u, 4.2f * u, 12 * u, 7.3f * u)
                cubicTo(14.2f * u, 4.2f * u, 19.5f * u, 5.6f * u, 19.5f * u, 10.1f * u)
                cubicTo(19.5f * u, 15.9f * u, 12 * u, 20.5f * u, 12 * u, 20.5f * u); close()
            }
            if (kind == G.HEART_FULL) drawPath(p, tint); drawPath(p, tint, style = st)
        }
        G.BIN -> { line(4.5f, 7f, 19.5f, 7f); drawPath(path { moveTo(9.5f * u, 7 * u); lineTo(9.5f * u, 4.5f * u); lineTo(14.5f * u, 4.5f * u); lineTo(14.5f * u, 7 * u) }, tint, style = st)
            drawPath(path { moveTo(6.5f * u, 7 * u); lineTo(7.4f * u, 19.5f * u); lineTo(16.6f * u, 19.5f * u); lineTo(17.5f * u, 7 * u) }, tint, style = st) }
        G.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        G.X -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        G.CHECK -> drawPath(path { moveTo(5 * u, 12.5f * u); lineTo(9.5f * u, 17 * u); lineTo(19 * u, 7.5f * u) }, tint, style = Stroke(2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        G.BACK -> drawPath(path { moveTo(14.5f * u, 5.5f * u); lineTo(8 * u, 12 * u); lineTo(14.5f * u, 18.5f * u) }, tint, style = st)
        G.UNDO -> { drawPath(path { moveTo(8 * u, 5 * u); lineTo(4 * u, 9 * u); lineTo(8 * u, 13 * u) }, tint, style = st)
            drawPath(path { moveTo(4 * u, 9 * u); lineTo(14 * u, 9 * u); cubicTo(21.5f * u, 9 * u, 21.5f * u, 20 * u, 14 * u, 20 * u); lineTo(11 * u, 20 * u) }, tint, style = st) }
        G.TUNE -> { line(4f, 8f, 20f, 8f); line(4f, 16f, 20f, 16f); drawCircle(W.Bg, 3.2f * u, Offset(9 * u, 8 * u)); drawCircle(tint, 2.6f * u, Offset(9 * u, 8 * u), style = st)
            drawCircle(W.Bg, 3.2f * u, Offset(15 * u, 16 * u)); drawCircle(tint, 2.6f * u, Offset(15 * u, 16 * u), style = st) }
        G.PLAY -> drawPath(path { moveTo(8 * u, 5.5f * u); lineTo(19 * u, 12 * u); lineTo(8 * u, 18.5f * u); close() }, tint)
        G.CAMERA -> { drawRoundRect(tint, Offset(3.5f * u, 7 * u), Size(17 * u, 12.5f * u), androidx.compose.ui.geometry.CornerRadius(3 * u), style = st); drawCircle(tint, 3.4f * u, Offset(12 * u, 13.2f * u), style = st); line(9f, 4.5f, 15f, 4.5f) }
        G.SHARE -> { drawPath(path { moveTo(12 * u, 15 * u); lineTo(12 * u, 4.5f * u) }, tint, style = st); drawPath(path { moveTo(8 * u, 8 * u); lineTo(12 * u, 4.2f * u); lineTo(16 * u, 8 * u) }, tint, style = st)
            drawPath(path { moveTo(5.5f * u, 12.5f * u); lineTo(5.5f * u, 19.5f * u); lineTo(18.5f * u, 19.5f * u); lineTo(18.5f * u, 12.5f * u) }, tint, style = st) }
    }
}
