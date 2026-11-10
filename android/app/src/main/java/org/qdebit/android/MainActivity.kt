package org.qdebit.android

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.qdebit.android.ui.HistoryScreen
import org.qdebit.android.ui.ImportDialog
import org.qdebit.android.ui.MeasureScreen
import org.qdebit.android.ui.PumpScreen
import org.qdebit.android.ui.Q
import org.qdebit.android.ui.QTheme
import org.qdebit.android.ui.WellScreen
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var app: AppState
    private val keyEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app = AppState(applicationContext)
        setContent { QTheme { Root(app, keyEvents) } }
    }

    // синк при возврате в приложение (и при старте — onResume вызывается и тогда)
    override fun onResume() {
        super.onResume()
        val c = app.sync
        if (c.enabled && c.auto && c.url.isNotBlank()) app.engine.syncNow()
        app.ensureElevations()
    }

    // кнопки громкости = старт/стоп на вкладке «Замер» (мокрые руки, экран не трогаем)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && app.tab == 0 && app.s.volKeys) {
            if (event.repeatCount == 0) keyEvents.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && app.tab == 0 && app.s.volKeys) return true
        return super.onKeyUp(keyCode, event)
    }
}

private val TABS = listOf("⏱" to "Замер", "🗂" to "История", "🕳" to "Скважина", "⚙️" to "Насос", "🗺" to "Участок", "☁" to "Синк")

@Composable
private fun Root(app: AppState, keys: SharedFlow<Unit>) {
    val ctx = LocalContext.current
    var pendingExport by remember { mutableStateOf("") }
    var importInfo by remember { mutableStateOf<AppState.ImportInfo?>(null) }

    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeUri(ctx, uri, pendingExport)
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) writeUri(ctx, uri, pendingExport)
    }
    val openJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            importInfo = app.parseImport(text)
        }.onFailure { Toast.makeText(ctx, "Не читается как бэкап QDebit: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    // горизонталь / планшет: навигация сбоку, верхняя строка компактнее — экран по высоте не съедается
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenWidthDp >= 600
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (wide) 4.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.qdebit_logo), contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text("QDebit", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Q.muted)
                Spacer(Modifier.weight(1f))
                val c = app.sync
                Text(
                    when {
                        !c.enabled -> "локально"
                        app.syncBusy -> "☁ …"
                        app.syncOk == false -> "☁ ошибка"
                        app.syncOk == true -> "☁ ок"
                        else -> "☁"
                    },
                    fontSize = 12.sp, color = if (app.syncOk == false && c.enabled) Q.crit else Q.secondary,
                    modifier = Modifier.clickable { app.tab = 5 },
                )
            }
            if (app.tab == 2 || app.tab == 3) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                org.qdebit.android.ui.Select("Скважина", app.liveWells.map { it.id to it.name }, app.W.id, { app.setActiveWell(it) }, Modifier.fillMaxWidth())
            }
            }
        },
        bottomBar = {
            if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                TABS.forEachIndexed { i, (ic, label) ->
                    NavigationBarItem(selected = app.tab == i, onClick = { app.tab = i }, icon = { Text(ic, fontSize = 20.sp) }, label = { Text(label, fontSize = 11.sp, maxLines = 1) })
                }
            }
        },
    ) { pad ->
        // любой текст на экранах можно выделить долгим нажатием и скопировать
        Row(Modifier.fillMaxSize().padding(pad)) {
            if (wide) androidx.compose.material3.NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                TABS.forEachIndexed { i, (ic, label) ->
                    androidx.compose.material3.NavigationRailItem(selected = app.tab == i, onClick = { app.tab = i }, icon = { Text(ic, fontSize = 18.sp) }, label = { Text(label, fontSize = 10.sp, maxLines = 1) })
                }
            }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 960.dp).fillMaxWidth()) {
            when (app.tab) {
                0 -> MeasureScreen(app, keys)
                1 -> HistoryScreen(app, onExport = { kind ->
                    val day = LocalDate.now().toString()
                    if (kind == "csv") { pendingExport = app.exportCsv(); saveCsv.launch("qdebit-$day.csv") }
                    else { pendingExport = app.exportJson(); saveJson.launch("qdebit-backup-$day.json") }
                }, onImport = { openJson.launch(arrayOf("application/json", "text/plain", "*/*")) })
                2 -> WellScreen(app)
                3 -> PumpScreen(app)
                4 -> org.qdebit.android.ui.SiteScreen(app)
                else -> org.qdebit.android.ui.SyncScreen(app)
            }
        } }
        }
    }

    importInfo?.let { info ->
        ImportDialog(info) { full ->
            importInfo = null
            if (full != null) {
                val n = app.applyImport(info.backup, full)
                Toast.makeText(ctx, "Добавлено замеров: $n" + if (full) "; настройки и скважины заменены" else "", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun writeUri(ctx: android.content.Context, uri: android.net.Uri, text: String) {
    runCatching {
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(ctx, "Не записалось: ${it.message}", Toast.LENGTH_LONG).show() }
}
