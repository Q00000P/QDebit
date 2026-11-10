package org.qdebit.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.qdebit.android.AppState
import org.qdebit.android.SyncConfig
import java.util.Date

@Composable
fun SyncScreen(app: AppState) {
    val c = app.sync
    fun up(f: (SyncConfig) -> SyncConfig) = app.updateSync(f)
    var showSecret by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        QCard("Синхронизация через InfluxDB") {
            Small("Скважины, замеры, свои насосы и пресеты хранятся в твоей InfluxDB (рядом с Home Assistant): " +
                "каждое устройство пишет туда свои правки и забирает чужие, новее — побеждает. " +
                "Замеры ещё и отдельными точками qdebit_flow — для Grafana.", modifier = Modifier.padding(bottom = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Small("Синк включён", modifier = Modifier.weight(1f), weight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
                Switch(checked = c.enabled, onCheckedChange = { v -> up { it.copy(enabled = v) }; if (v) app.engine.syncNow() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Small("Авто: после правок, при запуске и возврате в приложение", modifier = Modifier.weight(1f))
                Switch(checked = c.auto, onCheckedChange = { v -> up { it.copy(auto = v) } })
            }
            Select("Версия API", listOf("v1" to "v1 — InfluxDB 1.x (аддон HA), InfluxQL", "v2" to "v2 — InfluxDB 2.x, токен + Flux"), c.mode, { v -> up { it.copy(mode = v) } }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
            TextFieldQ("Адрес", c.url, { v -> up { it.copy(url = v.trim()) } }, Modifier.fillMaxWidth(), placeholder = "http://192.168.1.10:8086", kb = KeyboardType.Uri)
            if (c.mode == "v1") {
                TextFieldQ("База", c.db, { v -> up { it.copy(db = v.trim()) } }, Modifier.fillMaxWidth(), placeholder = "homeassistant")
                FieldGrid(
                    { m -> TextFieldQ("Пользователь", c.user, { v -> up { it.copy(user = v.trim()) } }, m) },
                    { m -> SecretField("Пароль", c.password, showSecret, { v -> up { it.copy(password = v) } }, m) },
                )
            } else {
                FieldGrid(
                    { m -> TextFieldQ("Org", c.org, { v -> up { it.copy(org = v.trim()) } }, m) },
                    { m -> TextFieldQ("Bucket", c.bucket, { v -> up { it.copy(bucket = v.trim()) } }, m, placeholder = "homeassistant") },
                )
                SecretField("Токен", c.token, showSecret, { v -> up { it.copy(token = v.trim()) } }, Modifier.fillMaxWidth())
            }
            TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "скрыть пароль/токен" else "показать пароль/токен") }
            Small("Пароль/токен хранятся только на этом телефоне: в экспорт JSON и в саму базу они не попадают. Лучше завести в Influx отдельного пользователя с правами только на эту базу.", color = Q.muted)
            if (app.syncBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !app.syncBusy && c.url.isNotBlank(), onClick = { app.engine.test() }) { Text("Проверить") }
                Button(enabled = !app.syncBusy && c.url.isNotBlank(), onClick = { app.engine.syncNow() }) { Text("Синк сейчас") }
            }
            OutlinedButton(enabled = !app.syncBusy && c.url.isNotBlank(), onClick = { app.engine.syncNow(full = true) }, modifier = Modifier.padding(top = 6.dp)) {
                Text("Отправить всё заново (и перезалить точки для Grafana)")
            }
            Small(
                (if (c.lastSyncAt > 0) "Последний синк: ${DATE_FMT.format(Date(c.lastSyncAt))}" else "Синка ещё не было") +
                    " · замеров ${app.measLive.size}, скважин ${app.liveWells.size}, своих насосов ${app.livePumps.size}",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        QCard("Журнал") {
            if (app.syncLog.isEmpty()) Small("Пусто. Нажми «Проверить» — покажет версию сервера и проверит чтение и запись.")
            app.syncLog.forEach { line ->
                Text(line, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = when { line.contains("✕") -> Q.crit; line.contains("✓") -> Q.good; else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface },
                    modifier = Modifier.padding(vertical = 2.dp))
            }
            if (app.syncLog.isNotEmpty()) TextButton(onClick = { app.syncLog = emptyList() }) { Text("Очистить журнал") }
        }
        QCard("Grafana") {
            Small("Измерение qdebit_flow: теги well_name, well, id; поля q (м³/ч), lpm, lps, n (вёдер), cv и err (%), dyn (м), pump_min. Время точки — момент замера.\n\n" +
                "Пример InfluxQL: SELECT \"q\" FROM \"qdebit_flow\" WHERE \$timeFilter GROUP BY \"well_name\"\n\n" +
                "Готовый дашборд — grafana/qdebit-dashboard.json в проекте (Dashboards → Import, источник выбирается при импорте).")
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, show: Boolean, onValue: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onValue, modifier = modifier, singleLine = true,
        label = { Text(label, fontSize = 12.sp) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
