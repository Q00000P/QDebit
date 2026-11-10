package org.qdebit.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- палитра (как в вебе: серии синий/оранжевый/аква, статусы отдельно) ----------
data class QColors(
    val series: List<Color>, val good: Color, val goodBg: Color, val warn: Color, val warnBg: Color,
    val crit: Color, val critBg: Color, val muted: Color, val secondary: Color, val grid: Color,
    val surface2: Color, val start: Color, val stop: Color, val ink: Color,
)

val DarkQ = QColors(
    listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70)),
    Color(0xFF4CC26A), Color(0xFF16301D), Color(0xFFE3B341), Color(0xFF352A0E), Color(0xFFF07070), Color(0xFF3A1717),
    Color(0xFF8F8E86), Color(0xFFC3C2B7), Color(0xFF2A2A27), Color(0xFF242422), Color(0xFF2C9A4A), Color(0xFFD94040), Color(0xFFF0EFEC),
)
val LightQ = QColors(
    listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A)),
    Color(0xFF1A7F37), Color(0xFFE3F3E7), Color(0xFF9A6700), Color(0xFFFDF1D6), Color(0xFFC62828), Color(0xFFFBE4E4),
    Color(0xFF7C7B76), Color(0xFF52514E), Color(0xFFE6E4DF), Color(0xFFEEEDE9), Color(0xFF1A7F37), Color(0xFFC62828), Color(0xFF0B0B0B),
)

private val darkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF3987E5), onPrimary = Color.White, background = Color(0xFF111110), onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF1A1A19), onSurface = Color(0xFFF0EFEC), surfaceVariant = Color(0xFF242422), onSurfaceVariant = Color(0xFFC3C2B7),
    outline = Color(0xFF3A3A36), outlineVariant = Color(0xFF33332F), surfaceContainer = Color(0xFF1A1A19),
)
private val lightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2A78D6), onPrimary = Color.White, background = Color(0xFFF3F3F1), onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFFCFCFB), onSurface = Color(0xFF0B0B0B), surfaceVariant = Color(0xFFEEEDE9), onSurfaceVariant = Color(0xFF52514E),
    outline = Color(0xFFCFCDC6), outlineVariant = Color(0xFFDCDAD4), surfaceContainer = Color(0xFFFCFCFB),
)

var Q: QColors = DarkQ
    private set

@Composable
fun QTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    Q = if (dark) DarkQ else LightQ
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
}

// ---------- строительные блоки ----------
@Composable
fun QCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 10.dp))
            content()
        }
    }
}

@Composable
fun H3(t: String) = Text(t.uppercase(), fontSize = 12.sp, color = Q.secondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

/** текст, который можно выделить долгим нажатием и скопировать (без глобального SelectionContainer — он ломал нажатия) */
@Composable
fun Sel(content: @Composable () -> Unit) = androidx.compose.foundation.text.selection.SelectionContainer { content() }

@Composable
fun Small(t: String, color: Color = Q.secondary, modifier: Modifier = Modifier, weight: FontWeight? = null, selectable: Boolean = true) {
    if (selectable) Sel { Text(t, fontSize = 12.5.sp, color = color, modifier = modifier, fontWeight = weight) }
    else Text(t, fontSize = 12.5.sp, color = color, modifier = modifier, fontWeight = weight)
}

enum class NoteKind { OK, WARN, CRIT, INFO }

@Composable
fun Note(kind: NoteKind, text: String) {
    val (sym, fg, bg) = when (kind) {
        NoteKind.OK -> Triple("✓", Q.good, Q.goodBg)
        NoteKind.WARN -> Triple("!", Q.warn, Q.warnBg)
        NoteKind.CRIT -> Triple("✕", Q.crit, Q.critBg)
        NoteKind.INFO -> Triple("i", MaterialTheme.colorScheme.primary, Q.surface2)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(sym, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(16.dp))
        Sel { Text(text, fontSize = 13.sp) }
    }
}

@Composable
fun Badge(kind: NoteKind, text: String) {
    val (fg, bg) = when (kind) { NoteKind.OK -> Q.good to Q.goodBg; NoteKind.WARN -> Q.warn to Q.warnBg; NoteKind.CRIT -> Q.crit to Q.critBg; else -> Q.secondary to Q.surface2 }
    Text(text, color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 1.dp))
}

@Composable
fun Hero(value: String, unit: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text("  $unit", fontSize = 16.sp, color = Q.secondary, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** плитки «значение / подпись» по cols в ряд (на широком экране — по 4) */
@Composable
fun KV(items: List<Pair<String, String>>, cols0: Int = 2) = androidx.compose.foundation.layout.BoxWithConstraints {
    val cols = if (maxWidth > 600.dp) maxOf(cols0, 4) else cols0
    Sel { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (v, l) ->
                    Column(Modifier.weight(1f).background(Q.surface2, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(v, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(l, fontSize = 11.5.sp, color = Q.secondary)
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } }
}

/** сетка полей: по 2 в ряд, на широком экране (горизонталь, планшет) — по 3–4 */
@Composable
fun FieldGrid(vararg cells: @Composable (Modifier) -> Unit) = androidx.compose.foundation.layout.BoxWithConstraints {
    val cols = when { maxWidth > 840.dp -> 4; maxWidth > 560.dp -> 3; else -> 2 }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        cells.toList().chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
fun numText(v: Double?): String = when {
    v == null -> ""
    v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e12 -> v.toLong().toString()
    else -> v.toString()
}

/** числовое поле: своя строка ввода, наружу — Double? */
@Composable
fun NumField(
    label: String, value: Double?, onValue: (Double?) -> Unit, modifier: Modifier = Modifier,
    hint: String? = null, placeholder: String? = null, enabled: Boolean = true,
) {
    var text by remember { mutableStateOf(numText(value)) }
    LaunchedEffect(value) { if (parseNum(text) != value) text = numText(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; onValue(parseNum(t)) },
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        label = { Text(label, fontSize = 12.sp, maxLines = 1) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = hint?.let { { Text(it, fontSize = 11.sp, maxLines = 2) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
fun TextFieldQ(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null, singleLine: Boolean = true, minLines: Int = 1, kb: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onValue, modifier = modifier, singleLine = singleLine, minLines = minLines,
        label = { Text(label, fontSize = 12.sp, maxLines = 1) }, placeholder = placeholder?.let { { Text(it, color = Q.muted) } },
        keyboardOptions = KeyboardOptions(keyboardType = kb),
    )
}

/** выпадающий выбор без экспериментальных API */
@Composable
fun <T> Select(label: String, options: List<Pair<T, String>>, value: T, onValue: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Q.secondary, modifier = Modifier.padding(bottom = 2.dp))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Text(options.firstOrNull { it.first == value }?.second ?: "—", maxLines = 1, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Text(" ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, t) -> DropdownMenuItem(text = { Text(t) }, onClick = { onValue(v); open = false }) }
            }
        }
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val bg = if (on) MaterialTheme.colorScheme.primary else Q.surface2
    val fg = if (on) Color.White else MaterialTheme.colorScheme.onSurface
    Text(
        text, color = fg, fontWeight = if (on) FontWeight.SemiBold else null, fontSize = 15.sp,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * Таблица: первая колонка слева, остальные справа. Если колонок много, а экран узкий (меньше ~95 dp на колонку),
 * строки показываются карточками «заголовок: значение» — без переноса по буквам.
 */
@Composable
fun QTable(header: List<String>?, rows: List<List<String>>, weights: List<Float>? = null, highlight: (Int) -> Boolean = { false }) =
    androidx.compose.foundation.layout.BoxWithConstraints {
    val n = header?.size ?: rows.firstOrNull()?.size ?: 0
    val w = weights ?: List(n) { if (it == 0) 1.6f else 1f }
    val stacked = header != null && n >= 3 && maxWidth / n < 95.dp
    Sel { Column {
        if (stacked) {
            rows.forEachIndexed { ri, r ->
                Column(Modifier.fillMaxWidth().background(if (highlight(ri)) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent).padding(vertical = 6.dp)) {
                    Text(r.getOrElse(0) { "" }, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    for (i in 1 until r.size) Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                        Text(header.getOrElse(i) { "" }, fontSize = 12.5.sp, color = Q.secondary, modifier = Modifier.weight(1f))
                        Text(r[i], fontSize = 13.5.sp, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        } else {
            header?.let { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    h.forEachIndexed { i, c -> Text(c, fontSize = 11.5.sp, color = Q.secondary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(w[i]), textAlign = if (i == 0) TextAlign.Start else TextAlign.End) }
                }
            }
            rows.forEachIndexed { ri, r ->
                Row(Modifier.fillMaxWidth().background(if (highlight(ri)) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent).padding(vertical = 6.dp)) {
                    r.forEachIndexed { i, c -> Text(c, fontSize = 13.5.sp, modifier = Modifier.weight(w[i]), textAlign = if (i == 0) TextAlign.Start else TextAlign.End) }
                }
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    } }
}
