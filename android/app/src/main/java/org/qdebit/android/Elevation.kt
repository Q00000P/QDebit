package org.qdebit.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Высота поверхности по координатам из открытых моделей рельефа (без ключей):
 * 1) Open-Meteo Elevation — Copernicus DEM GLO-90; 2) запасной — OpenTopoData SRTM 30 м.
 * Точность абсолютной высоты ±2–5 м (в лесу/застройке хуже) — для атмосферного давления хватает,
 * для перепада между скважинами одного участка нужна своя отметка (нивелир/лазер).
 * Возвращает высоты по порядку точек и название источника.
 */
object Elevation {
    fun fetch(pts: List<Pair<Double, Double>>): Pair<List<Double?>, String> {
        val out = ArrayList<Double?>(); var src = ""
        for (chunk in pts.chunked(50)) {
            val r = runCatching { openMeteo(chunk) to "Copernicus DEM (Open-Meteo)" }.recoverCatching { openTopo(chunk) to "SRTM 30 м (OpenTopoData)" }.getOrThrow()
            out += r.first; src = r.second
        }
        return out to src
    }

    private fun f(x: Double) = String.format(Locale.US, "%.6f", x)

    private fun openMeteo(pts: List<Pair<Double, Double>>): List<Double?> {
        val u = "https://api.open-meteo.com/v1/elevation?latitude=" + pts.joinToString(",") { f(it.first) } + "&longitude=" + pts.joinToString(",") { f(it.second) }
        val j = QJson.parseToJsonElement(get(u)).jsonObject
        val arr = j["elevation"]?.jsonArray ?: error("нет elevation")
        return arr.map { it.jsonPrimitive.doubleOrNull?.takeIf { v -> v > -1000 } }.also { if (it.size != pts.size) error("ответ не той длины") }
    }

    private fun openTopo(pts: List<Pair<Double, Double>>): List<Double?> {
        val u = "https://api.opentopodata.org/v1/srtm30m?locations=" + pts.joinToString("|") { f(it.first) + "," + f(it.second) }
        val j = QJson.parseToJsonElement(get(u)).jsonObject
        val arr = (j["results"] as? JsonArray) ?: error("нет results")
        return arr.map { (it as? JsonObject)?.get("elevation")?.jsonPrimitive?.doubleOrNull }
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "QDebit/${BuildConfig.VERSION_NAME} (Android)")
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
