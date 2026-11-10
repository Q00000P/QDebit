package org.qdebit.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.qdebit.android.BuildConfig
import org.qdebit.android.Core
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

// ---------- спутниковые подложки: Google и Яндекс ----------

private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

/** Google: спутник (s), гибрид с подписями (y), схема (m); подписи по-русски. osmdroid грузит и кэширует сам; глубже max — растягивает загруженные. */
private fun googleSource(name: String, lyrs: String, max: Int) = object : OnlineTileSourceBase(
    name, 1, max, 256, if (lyrs == "m") ".png" else ".jpg",
    arrayOf(0, 1, 2, 3).map { "https://mt$it.google.com/vt/lyrs=$lyrs&hl=ru" }.toTypedArray(),
    "© Google",
) {
    override fun getTileURLString(i: Long): String =
        baseUrl + "&x=" + MapTileIndex.getX(i) + "&y=" + MapTileIndex.getY(i) + "&z=" + MapTileIndex.getZoom(i)
}
private val GOOGLE_SAT = googleSource("GoogleSat", "s", 20)
private val GOOGLE_HYB = googleSource("GoogleHybrid", "y", 20)
private val GOOGLE_MAP = googleSource("GoogleMap", "m", 20)

/**
 * Яндекс: спутник (sat), схема (map), подписи для гибрида (skl, прозрачные). Яндекс режет тайлы в эллипсоидном Меркаторе (EPSG:3395),
 * а карта — в сферическом (EPSG:3857): без пересчёта картинка съезжает по вертикали. Поэтому каждый тайл карты собирается
 * построчно из 1–2 тайлов Яндекса. Готовые тайлы лежат в кэше приложения — увиденное работает без сети.
 */
private const val YANDEX_MAX = 19
private fun yandexSource(layer: String) = object : OnlineTileSourceBase(
    "Yandex-$layer", 1, YANDEX_MAX, 256, if (layer == "sat") ".jpg" else ".png", arrayOf("https://core-sat.maps.yandex.net/tiles"), "© Яндекс",
) {
    override fun getTileURLString(i: Long): String = baseUrl  // не используется: тайлы собирает YandexModule
}

private const val E_WGS84 = 0.0818191908426

/** номер пиксельной строки Яндекса (мировые пиксели) для строки веб-Меркатора */
private fun yandexRow(worldY: Double, worldSize: Double): Double {
    val lat = atan(sinh(PI * (1 - 2 * worldY / worldSize)))
    val s = sin(lat)
    val m = ln(tan(PI / 4 + lat / 2) * ((1 - E_WGS84 * s) / (1 + E_WGS84 * s)).pow(E_WGS84 / 2))
    return worldSize / 2 * (1 - m / PI)
}

internal class YandexModule(private val ctx: Context, private val layer: String) : MapTileModuleProviderBase(4, 60) {
    private val dir = File(ctx.cacheDir, "yandex-$layer")
    private val png = layer != "sat"

    override fun getName(): String = "Yandex-$layer"
    override fun getThreadGroupName(): String = "yandex$layer"
    override fun getUsesDataConnection(): Boolean = true
    override fun getMinimumZoomLevel(): Int = 1
    override fun getMaximumZoomLevel(): Int = YANDEX_MAX
    override fun setTileSource(tileSource: ITileSource?) {}
    override fun getTileLoader(): TileLoader = Loader()

    inner class Loader : TileLoader() {
        override fun loadTile(pMapTileIndex: Long): Drawable? {
            val z = MapTileIndex.getZoom(pMapTileIndex); val x = MapTileIndex.getX(pMapTileIndex); val y = MapTileIndex.getY(pMapTileIndex)
            val f = File(dir, "$z/$x/$y." + if (png) "png" else "jpg")
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return BitmapDrawable(ctx.resources, it) }
            val bmp = compose(z, x, y) ?: return null
            runCatching { f.parentFile?.mkdirs(); f.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) } }
            return BitmapDrawable(ctx.resources, bmp)
        }
    }

    private fun compose(z: Int, x: Int, y: Int): Bitmap? {
        val t = 256
        val world = t.toDouble() * (1L shl z)
        val rows = IntArray(t) { r -> floor(yandexRow(y * t + r + 0.5, world)).toInt().coerceIn(0, world.toInt() - 1) }
        val t0 = rows.first() / t; val t1 = rows.last() / t
        val px = (t0..t1).map { ty ->
            val b = fetch(z, x, ty) ?: return null
            val bb = if (b.width == t && b.height == t) b else Bitmap.createScaledBitmap(b, t, t, true)
            IntArray(t * t).also { bb.getPixels(it, 0, t, 0, 0, t, t) }
        }
        val out = IntArray(t * t)
        for (r in 0 until t) {
            val k = rows[r] / t - t0
            val sr = rows[r] - (t0 + k) * t
            System.arraycopy(px[k], sr * t, out, r * t, t)
        }
        return Bitmap.createBitmap(out, t, t, Bitmap.Config.ARGB_8888)
    }

    private fun fetch(z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 + (x + y) % 4
        val q = "x=$x&y=$y&z=$z&scale=1&lang=ru_RU"
        val urls = when (layer) {
            "sat" -> listOf("https://core-sat.maps.yandex.net/tiles?l=sat&$q", "https://sat0$n.maps.yandex.net/tiles?l=sat&$q")
            else -> listOf("https://core-renderer-tiles.maps.yandex.net/tiles?l=$layer&$q", "https://vec0$n.maps.yandex.net/tiles?l=$layer&$q")
        }
        for (u in urls) {
            val c = runCatching { URL(u).openConnection() as HttpURLConnection }.getOrNull() ?: continue
            try {
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", BROWSER_UA)
                c.setRequestProperty("Referer", "https://yandex.ru/maps/")
                if (c.responseCode == 404) return null          // на этом уровне данных нет
                if (c.responseCode != 200) continue
                c.inputStream.use { s -> BitmapFactory.decodeStream(s)?.let { return it } }
            } catch (_: Exception) {
            } finally { c.disconnect() }
        }
        return null
    }
}

data class MapSource(val id: String, val label: String)

/** режимы отображения: спутник / гибрид (спутник + подписи и дороги) / схема — у Google и Яндекса */
val MAP_SOURCES = listOf(
    MapSource("google-sat", "Google спутник"), MapSource("google-hyb", "Google гибрид"), MapSource("google-map", "Google схема"),
    MapSource("yandex-sat", "Яндекс спутник"), MapSource("yandex-hyb", "Яндекс гибрид"), MapSource("yandex-map", "Яндекс схема"),
)
/** старые значения настройки (до 1.6) → новые */
fun normMapSource(id: String?): String = when (id) {
    null, "", "google" -> "google-sat"; "yandex" -> "yandex-sat"
    else -> if (MAP_SOURCES.any { it.id == id }) id else "google-sat"
}

private fun yandexProvider(ctx: Context, layer: String): MapTileProviderBase {
    val m = YandexModule(ctx, layer)
    val approx = MapTileApproximater().apply { addProvider(m) }   // глубже 19-го — растягиваем уже собранные тайлы
    return MapTileProviderArray(yandexSource(layer), null, arrayOf(m, approx))
}

private fun providerFor(ctx: Context, id: String): MapTileProviderBase = when (normMapSource(id)) {
    "google-hyb" -> MapTileProviderBasic(ctx, GOOGLE_HYB)
    "google-map" -> MapTileProviderBasic(ctx, GOOGLE_MAP)
    "yandex-sat", "yandex-hyb" -> yandexProvider(ctx, "sat")
    "yandex-map" -> yandexProvider(ctx, "map")
    else -> MapTileProviderBasic(ctx, GOOGLE_SAT)
}

data class MapPoint(val id: String, val name: String, val lat: Double, val lon: Double, val label: String, val active: Boolean)

/** точки скважин с подписями + пунктир с расстояниями + «я» + перекрестие в центре (только в полноэкранной карте) */
internal class WellsOverlay(val ctx: Context) : Overlay() {
    var items: List<MapPoint> = emptyList()
    var me: Triple<Double, Double, Double>? = null      // lat, lon, точность м
    var crossLabel: String? = null
    var showDist: Boolean = true                          // пунктир и расстояния между всеми скважинами
    var onTap: (String) -> Unit = {}
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(57, 135, 229) }
    private val fillAct = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(255, 196, 0) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; textSize = 14 * d; isFakeBoldText = true; setShadowLayer(4 * d, 0f, 0f, AColor.BLACK) }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(230, 230, 230); textSize = 12 * d; setShadowLayer(4 * d, 0f, 0f, AColor.BLACK) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.argb(200, 255, 255, 255); strokeWidth = 1.5f * d; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
    }
    private val meFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(66, 133, 244) }
    private val meAcc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(50, 66, 133, 244) }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE; strokeWidth = 2f * d; style = Paint.Style.STROKE }
    private val crossShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(160, 0, 0, 0); strokeWidth = 4f * d; style = Paint.Style.STROKE }

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = map.projection
        me?.let { (la, lo, acc) ->
            val p = pj.toPixels(GeoPoint(la, lo), Point())
            val r = pj.metersToPixels(acc.toFloat())
            if (r > 10 * d) c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, meAcc)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 7 * d, meFill)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 7 * d, ring)
        }
        val px = items.map { pj.toPixels(GeoPoint(it.lat, it.lon), Point()) }
        if (showDist && items.size >= 2) for (i in items.indices) for (j in i + 1 until items.size) {
            c.drawLine(px[i].x.toFloat(), px[i].y.toFloat(), px[j].x.toFloat(), px[j].y.toFloat(), line)
            // подпись — если отрезок на экране не слишком короткий (иначе каша)
            if (hypot((px[i].x - px[j].x).toDouble(), (px[i].y - px[j].y).toDouble()) > 70 * d) {
                val m = Core.distM(items[i].lat, items[i].lon, items[j].lat, items[j].lon)
                c.drawText(Core.fmtDist(m), (px[i].x + px[j].x) / 2f + 4 * d, (px[i].y + px[j].y) / 2f - 4 * d, sub)
            }
        }
        // точки, которые на экране почти совпадают, подписываем одним блоком строк — иначе надписи ложатся друг на друга
        val done = BooleanArray(items.size)
        val order = items.indices.sortedBy { if (items[it].active) 1 else 0 }   // активная рисуется последней — сверху
        order.forEach { i ->
            val it = items[i]; val p = px[i]
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), (if (it.active) 9 else 7) * d, if (it.active) fillAct else fill)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), (if (it.active) 9 else 7) * d, ring)
        }
        items.indices.forEach { i ->
            if (done[i]) return@forEach
            val grp = items.indices.filter { j -> !done[j] && hypot((px[i].x - px[j].x).toDouble(), (px[i].y - px[j].y).toDouble()) < 28 * d }
            grp.forEach { done[it] = true }
            val p = px[i]
            var y = p.y - 2 * d
            grp.forEach { j ->
                c.drawText(items[j].name, p.x + 14 * d, y, text)
                c.drawText(items[j].label, p.x + 14 * d, y + 15 * d, sub)
                y += 34 * d
            }
        }
        crossLabel?.let { lbl ->
            val cx = map.width / 2f; val cy = map.height / 2f; val a = 16 * d; val g = 4 * d
            for (pt in listOf(crossShadow, cross)) {
                c.drawLine(cx - a, cy, cx - g, cy, pt); c.drawLine(cx + g, cy, cx + a, cy, pt)
                c.drawLine(cx, cy - a, cx, cy - g, pt); c.drawLine(cx, cy + g, cx, cy + a, pt)
            }
            if (lbl.isNotEmpty()) c.drawText(lbl, cx + 8 * d, cy + a + 12 * d, sub)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val pj = map.projection
        val hit = items.minByOrNull { val p = pj.toPixels(GeoPoint(it.lat, it.lon), Point()); hypot((p.x - e.x).toDouble(), (p.y - e.y).toDouble()) } ?: return false
        val p = pj.toPixels(GeoPoint(hit.lat, hit.lon), Point())
        if (hypot((p.x - e.x).toDouble(), (p.y - e.y).toDouble()) > 32 * d) return false
        onTap(hit.id); return true
    }
}

/** Управление картой из Compose: центр (для «Поставить в перекрестие»), перелёт, «я», режим отображения. */
class SiteMapState {
    internal var map: MapView? = null
    internal var overlay: WellsOverlay? = null
    private var providerId: String? = null      // для какого режима стоит провайдер тайлов
    private var labels: org.osmdroid.views.overlay.TilesOverlay? = null

    fun center(): Pair<Double, Double>? = map?.mapCenter?.let { it.latitude to it.longitude }
    fun flyTo(lat: Double, lon: Double, zoom: Double? = null) {
        val m = map ?: return
        if (zoom != null) m.controller.animateTo(GeoPoint(lat, lon), zoom, 600L) else m.controller.animateTo(GeoPoint(lat, lon))
    }
    fun jumpTo(lat: Double, lon: Double) { map?.controller?.setCenter(GeoPoint(lat, lon)) }
    fun showMe(lat: Double, lon: Double, acc: Double) { overlay?.me = Triple(lat, lon, acc); map?.invalidate() }

    /** MapView создан уже с провайдером для id */
    internal fun attach(m: MapView, o: WellsOverlay, id: String) { map = m; overlay = o; providerId = id; labels = null }
    internal fun detach() { map = null; overlay = null; providerId = null; labels = null }
    internal fun setSource(ctx: Context, id: String) {
        val m = map ?: return
        if (providerId != id) { m.setTileProvider(providerFor(ctx, id)); providerId = id }
        val want = normMapSource(id) == "yandex-hyb"
        if (want && labels == null) {
            // гибрид Яндекса: поверх спутника — прозрачный слой подписей и дорог (тоже пересчитанный из EPSG:3395)
            val lp = yandexProvider(ctx, "skl").apply { tileRequestCompleteHandlers.add(org.osmdroid.tileprovider.util.SimpleInvalidationHandler(m)) }
            val o = org.osmdroid.views.overlay.TilesOverlay(lp, ctx).apply {
                loadingBackgroundColor = AColor.TRANSPARENT; loadingLineColor = AColor.TRANSPARENT
            }
            m.overlays.add(0, o); labels = o; m.invalidate()
        } else if (!want && labels != null) {
            labels?.let { m.overlays.remove(it); it.onDetach(m) }; labels = null; m.invalidate()
        }
    }
}

/**
 * Карта участка (osmdroid). interactive = false — превью в карточке: жесты не принимает, листать страницу не мешает.
 * interactive = true — полноэкранная: перекрестие, тап по точке делает скважину активной.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun SiteMap(
    state: SiteMapState, points: List<MapPoint>, start: Pair<Double, Double>, startZoom: Double, sourceId: String,
    interactive: Boolean, crossLabel: String?, modifier: Modifier, onTapWell: (String) -> Unit, showDist: Boolean = true,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val overlay = remember { WellsOverlay(ctx) }
    val tapState by androidx.compose.runtime.rememberUpdatedState(onTapWell)
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = BROWSER_UA
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        MapView(ctx, providerFor(ctx, sourceId)).apply {
            isTilesScaledToDpi = false          // тайлы 1:1 — максимально резко
            minZoomLevel = 3.0
            maxZoomLevel = 21.0                 // глубже паспорта источника — растянутые уже загруженные тайлы, а не «нет данных»
            controller.setZoom(startZoom)
            controller.setCenter(GeoPoint(start.first, start.second))
            overlays.add(CopyrightOverlay(ctx))
            if (interactive) {
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)   // зум только жестами
                overlays.add(ScaleBarOverlay(this).apply { setAlignBottom(true); setScaleBarOffset(16, 48) })
            } else {
                setMultiTouchControls(false)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isClickable = false
                setOnTouchListener { _, _ -> true }   // превью: никаких жестов
            }
            overlays.add(overlay)
        }
    }
    if (state.map !== map) state.attach(map, overlay, sourceId)
    state.setSource(ctx, sourceId)
    overlay.onTap = { tapState(it) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_RESUME -> map.onResume(); Lifecycle.Event.ON_PAUSE -> map.onPause(); else -> {} }
        }
        lifecycle.addObserver(obs); map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause(); map.onDetach(); state.detach() }
    }
    AndroidView(factory = { map }, modifier = modifier.clipToBounds(), update = {
        overlay.items = points
        overlay.crossLabel = crossLabel
        overlay.showDist = showDist
        state.setSource(ctx, sourceId)
        it.invalidate()
    })
}

/** Последняя известная позиция без ожидания (если есть разрешение). */
@SuppressLint("MissingPermission")
fun lastKnownLocation(ctx: Context): Location? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
}

/**
 * GPS: копим фиксы и усредняем лучшие по точности (взвешенно 1/acc²).
 * quick = true — «где я»: хватает 3 фиксов ±10 м или 12 с; иначе 8 фиксов ±5 м или 25 с.
 * onDone(lat, lon, accuracy м, высота м?, фиксов)
 */
class GpsAverager(private val ctx: Context) {
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    @SuppressLint("MissingPermission")
    fun start(quick: Boolean = false, onDone: (Double, Double, Double, Double?, Int) -> Unit) {
        if (running) return
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) { status = "GPS выключен в настройках телефона"; return }
        val fixes = ArrayList<Location>()
        val t0 = System.currentTimeMillis()
        running = true; status = "Жду спутники…"
        lm = m
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                fixes += loc
                val best = fixes.minOf { it.accuracy }
                status = "Фиксов: ${fixes.size}, лучшая точность ±${Core.f1(best.toDouble())} м"
                val dt = System.currentTimeMillis() - t0
                val enough = if (quick) fixes.count { it.accuracy <= 10f } >= 3 || dt > 12_000
                             else fixes.count { it.accuracy <= 5f } >= 8 || dt > 25_000
                if (enough) finish(onDone, fixes)
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) { status = "GPS выключили"; stop() }
        }
        listener = l
        m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, l, Looper.getMainLooper())
    }

    private fun finish(onDone: (Double, Double, Double, Double?, Int) -> Unit, fixes: List<Location>) {
        stop()
        val use = fixes.sortedBy { it.accuracy }.take(maxOf(1, (fixes.size * 0.6).toInt()))
        var sw = 0.0; var la = 0.0; var lo = 0.0
        use.forEach { val w = 1.0 / maxOf(1.0, it.accuracy.toDouble()).let { a -> a * a }; sw += w; la += it.latitude * w; lo += it.longitude * w }
        val acc = use.minOf { it.accuracy }.toDouble()
        val alt = use.filter { it.hasAltitude() }.map { it.altitude }.takeIf { it.isNotEmpty() }?.average()
        status = "Ты здесь: точность ≈ ±${Core.f1(acc)} м (${use.size} из ${fixes.size} фиксов)"
        onDone(la / sw, lo / sw, acc, alt, fixes.size)
    }

    fun stop() {
        listener?.let { lm?.removeUpdates(it) }
        listener = null; running = false
    }
}

@Composable
fun rememberLocationPermission(onGranted: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val cb by androidx.compose.runtime.rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) cb()
    }
    return {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) cb()
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
