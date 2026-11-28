package com.reparto.pisos

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un edificio guardado: su identificador y los nombres del timbre con el piso de cada uno. */
data class Building(
    val id: String,
    val entries: List<PanelEntry>,
    val savedAt: Long,
    val usedAi: Boolean,
)

object BuildingJson {

    fun encode(list: List<Building>): String {
        val arr = JSONArray()
        for (b in list) {
            val es = JSONArray()
            for (e in b.entries) {
                val o = JSONObject().put("t", e.text).put("g", e.guessed)
                e.floor?.let { o.put("f", it.order).put("fl", it.label) }
                es.put(o)
            }
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("savedAt", b.savedAt)
                    .put("ai", b.usedAi)
                    .put("entries", es),
            )
        }
        return JSONObject().put("buildings", arr).toString()
    }

    fun decode(text: String): List<Building> {
        val out = ArrayList<Building>()
        val arr = JSONObject(text).optJSONArray("buildings") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val entries = ArrayList<PanelEntry>()
            o.optJSONArray("entries")?.let { ea ->
                for (j in 0 until ea.length()) {
                    val eo = ea.optJSONObject(j) ?: continue
                    val floor = if (eo.has("f")) Floor(eo.optInt("f"), eo.optString("fl")) else null
                    entries.add(PanelEntry(eo.optString("t"), floor, eo.optBoolean("g", false)))
                }
            }
            out.add(Building(o.optString("id"), entries, o.optLong("savedAt"), o.optBoolean("ai")))
        }
        return out
    }
}

/** Guarda los edificios en un archivo del almacenamiento interno de la app. */
class BuildingStore(private val file: File) {

    fun load(): List<Building> =
        runCatching { if (file.exists()) BuildingJson.decode(file.readText()) else emptyList() }
            .getOrDefault(emptyList())

    /** Escribe primero a un archivo temporal para no corromper los datos si se corta a medias. */
    fun save(list: List<Building>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(BuildingJson.encode(list))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
