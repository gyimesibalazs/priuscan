package hu.codingo.priuscan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- shared presentation building blocks (cards, tab bar, chips, value tiles) ----
// Flat by design: 1 dp borders, no elevation / shadow / blur (low-end head-unit GPU).

/** Shortcut for the current theme tokens. */
val pc: PriusColors
    @Composable @ReadOnlyComposable get() = LocalPriusColors.current

val CardShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(12.dp)

/** Tabular (fixed-width) digits so values don't jitter at 4 Hz, without a monospace look. */
val NumStyle = TextStyle(fontFeatureSettings = "tnum")

/** Rounded card: card background + 1 dp border, no shadow. */
@Composable
fun PCard(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = pc
    Column(
        modifier.background(c.card, CardShape).border(1.dp, c.cardBorder, CardShape).padding(padding),
        verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

@Composable
fun SectionTitle(t: String, modifier: Modifier = Modifier) {
    Text(
        t.uppercase(), modifier, color = pc.dim, fontSize = 13.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** Card heading (settings / chart cards): larger, primary text colour. */
@Composable
fun CardTitle(t: String, modifier: Modifier = Modifier) {
    Text(t, modifier, color = pc.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Capsule tab bar: the selected tab is a filled accent pill, the others plain text separated by
 *  thin bars. Scrolls horizontally when the titles don't fit; otherwise spreads to full width. */
@Composable
fun CapsuleTabBar(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = pc
    BoxWithConstraints(
        modifier.fillMaxWidth().background(c.card, CardShape).border(1.dp, c.cardBorder, CardShape).padding(4.dp)
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            titles.forEachIndexed { i, t ->
                if (i > 0) {
                    val show = i != selected && i - 1 != selected
                    Box(Modifier.width(1.dp).height(16.dp).background(if (show) c.borderStrong else Color.Transparent))
                }
                val sel = i == selected
                Box(
                    Modifier.clip(PillShape)
                        .background(if (sel) c.accent else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t, color = if (sel) c.onAccent else c.text, fontSize = 15.sp, maxLines = 1,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Trip-selector chip: selected = filled purple, otherwise outlined. */
@Composable
fun PillChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = pc
    Box(
        Modifier.clip(PillShape)
            .background(if (selected) c.purple else Color.Transparent)
            .border(1.dp, if (selected) c.purple else c.borderStrong, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) c.onPurple else c.text, fontSize = 15.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Small bordered info chip, e.g. "Block min: 16.78 V". */
@Composable
fun InfoChip(label: String, value: String) {
    val c = pc
    Row(
        Modifier.border(1.dp, c.borderStrong, PillShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label: ", color = c.dim, fontSize = 13.sp, maxLines = 1)
        Text(value, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = NumStyle, maxLines = 1)
    }
}

/** Thin static progress bar (rounded track + fill). Not animated. */
@Composable
fun ThinBar(frac: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val track = pc.track
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
    }
}

/** Label-left / value-right row inside a card, with an optional bottom divider. */
@Composable
fun StatRow(label: String, value: String, highlight: Boolean = false, divider: Boolean = true) {
    val c = pc
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f, fill = false).padding(end = 16.dp),
                color = if (highlight) c.amberText else c.text, fontSize = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = if (highlight) c.amberText else c.text, fontSize = 17.sp,
                style = NumStyle, maxLines = 1, softWrap = false)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

/** One value tile. value = formatted number (or symbol), unit rendered smaller next to it. */
data class TileSpec(
    val label: String,
    val value: String,
    val unit: String = "",
    val color: Color? = null,       // explicit value colour (e.g. coolant ramp)
    val highlight: Boolean = false, // amber value (active state / weakest block)
    val bar: Float? = null,         // hero tiles: 0..1 progress (only for intrinsic 0..100 % values)
)

@Composable
fun ValueTile(s: TileSpec, modifier: Modifier = Modifier, hero: Boolean = false, compact: Boolean = false) {
    val c = pc
    PCard(modifier, padding = if (compact) 10.dp else 14.dp) {
        Text(s.label, color = c.dim, fontSize = if (hero) 16.sp else if (compact) 13.sp else 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        Row {
            val deg = s.unit == "°"          // a lone degree sign is too small as a unit -> glue it to the value
            Text(
                if (deg) s.value + "°" else s.value, Modifier.alignByBaseline(),
                color = s.color ?: if (s.highlight) c.amberText else c.text,
                fontSize = if (hero) 40.sp else if (compact) 20.sp else 28.sp,
                fontWeight = FontWeight.SemiBold, style = NumStyle, maxLines = 1, softWrap = false,
            )
            if (s.unit.isNotEmpty() && !deg) {
                Spacer(Modifier.width(5.dp))
                Text(s.unit, Modifier.alignByBaseline(), color = c.dim,
                    fontSize = if (hero) 18.sp else if (compact) 12.sp else 15.sp, maxLines = 1, softWrap = false)
            }
        }
        if (hero) {
            Spacer(Modifier.height(10.dp))
            if (s.bar != null) ThinBar(s.bar, s.color ?: c.accent) else Spacer(Modifier.height(6.dp))
        }
    }
}

/** Responsive column count for a content width. */
fun gridCols(width: Dp): Int = when {
    width >= 960.dp -> 4
    width >= 700.dp -> 3
    width >= 420.dp -> 2
    else -> 1
}

/** Generic tile grid: [specs] laid out row by row, [cols] per row; the first [heroCount] tiles
 *  are rendered as hero tiles (bigger value). Empty slots at the end keep the column widths. */
@Composable
fun TileGrid(specs: List<TileSpec>, cols: Int, heroCount: Int = 0, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        val hero = specs.take(heroCount)
        val rest = specs.drop(heroCount)
        for (group in listOf(hero, rest)) {
            group.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    row.forEach { s -> ValueTile(s, Modifier.weight(1f), hero = group === hero, compact = compact) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Scrollable tab page: full width (capped at 1400 dp, centred) with 12 dp gutters.
 *  The content lambda gets the responsive column count. */
@Composable
fun TabPage(content: @Composable ColumnScope.(cols: Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val w = minOf(maxWidth, 1400.dp) - 24.dp
        Column(
            Modifier.widthIn(max = 1400.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content(gridCols(w))
            Spacer(Modifier.height(12.dp))
        }
    }
}
