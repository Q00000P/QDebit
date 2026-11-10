package org.qdebit.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.qdebit.android.Core
import org.qdebit.android.Pump

/** Выбор насоса с поиском: набираешь «4 142», «водомет 55», «jsw» — список сужается. Свои и нужного типа — выше. */
@Composable
fun PumpPickerField(
    label: String, pumps: List<Pump>, selectedId: String?, onPick: (Pump?) -> Unit,
    modifier: Modifier = Modifier, preferType: String? = null, allowClear: Boolean = true, buttonText: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val sel = pumps.firstOrNull { it.id == selectedId }
    Column(modifier) {
        if (label.isNotEmpty()) Text(label, fontSize = 12.sp, color = Q.secondary, modifier = Modifier.padding(bottom = 2.dp))
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
            Text(buttonText ?: sel?.name ?: "— не выбран —", maxLines = 1, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Text(" 🔍")
        }
    }
    if (open) PumpPickerDialog(pumps, preferType, allowClear, onDismiss = { open = false }) { p -> open = false; onPick(p) }
}

@Composable
private fun PumpPickerDialog(pumps: List<Pump>, preferType: String?, allowClear: Boolean, onDismiss: () -> Unit, onPick: (Pump?) -> Unit) {
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun rank(p: Pump) = (if (p.catalog) 1 else 0) + (if (preferType != null && Core.pumpType(p) != preferType) 2 else 0)
    val sorted = remember(pumps, preferType) { pumps.sortedWith(compareBy<Pump>({ rank(it) }, { it.name })) }
    val found = if (q.isBlank()) sorted else sorted.filter { Core.matchName(it.name, q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Насос") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    placeholder = { Text("4-142, водомет 55, jsw, dab s4…") }, label = { Text("Поиск · найдено ${found.size}") })
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 6.dp)) {
                    if (allowClear) item {
                        Text("— не выбран —", Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 10.dp), color = Q.muted)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    items(found, key = { it.id }) { p ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 8.dp)) {
                            Text(p.name, fontSize = 15.sp)
                            Text(listOfNotNull(Core.TYPE_NAMES[Core.pumpType(p)], if (p.catalog) "каталог" else "свой", p.diam?.let { "Ø${Core.f0(it)}" },
                                p.powerKW?.let { "${Core.f2(it)} кВт" }, if (p.approx) "≈ кривая" else null).joinToString(" · "), fontSize = 12.sp, color = Q.muted)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
