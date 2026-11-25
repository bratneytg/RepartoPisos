package com.reparto.pisos

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Modo IA (opcional): manda las fotos y los nombres a la API de Claude, que lee el timbre
 * y devuelve el piso de cada persona. Es mucho más fiable que la lectura local cuando el
 * timbre no tiene números de piso claros.
 */
object AiClient {

    private val PROMPT_HEAD = """
You are helping a parcel courier. The attached photo(s) show the doorbell panel of a building: resident names grouped by floor.

TASK: for every parcel recipient listed at the end, say on which floor they live.

Rules:
- Read every name on the panel carefully and work out the floor of each one from the layout (floor labels such as EG, 1. OG, 2, 3º, row or section headers, separator lines, gaps). Numbering: ground floor (EG / planta baja / PB) = 0, basement (UG / KG) = -1, 1. OG / 1st floor = 1, and so on. If a floor is not labelled, infer it from the layout and mention that in "note".
- Recipient names may be written "First Last", "Last, First" or only a surname. Ignore differences in accents/umlauts (ü = ue = u) and small reading variations.
- Several residents can share a surname. Then use the first name or the initial letter shown on the panel (e.g. "J. Müller") to choose the right one. If the panel does not allow choosing, return all candidates in "matches".
- Never invent a floor you cannot see. If the person is not on the panel, return an empty "matches" list.
- Answer with ONLY a JSON object, no markdown, in this exact shape:
{"panel":[{"floor":3,"text":"J. Müller"}],"results":[{"id":1,"matches":[{"floor":3,"panel_text":"J. Müller"}],"confidence":"high","note":""}]}
- "floor" is an integer, or null if unknown. "confidence" is "high", "medium" or "low". Include in "panel" every name you read.

Recipients:
""".trimIndent()

    fun buildPrompt(parcels: List<Parcel>): String =
        PROMPT_HEAD + "\n" + parcels.mapIndexed { i, p -> "${i + 1}. ${p.name}" }.joinToString("\n")

    fun extractJson(text: String): JSONObject {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) throw IOException("La IA no devolvió datos legibles")
        return JSONObject(text.substring(s, e + 1))
    }

    private fun floorFrom(o: JSONObject): Floor? =
        if (!o.has("floor") || o.isNull("floor")) null else Floor.number(o.optInt("floor"))

    fun toResults(parcels: List<Parcel>, json: JSONObject): Pair<List<ParcelResult>, List<PanelEntry>> {
        val panel = ArrayList<PanelEntry>()
        json.optJSONArray("panel")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                panel.add(PanelEntry(o.optString("text"), floorFrom(o), false))
            }
        }

        val byId = HashMap<Int, JSONObject>()
        val arr = json.optJSONArray("results") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            byId[o.optInt("id", -1)] = o
        }

        val results = parcels.mapIndexed { idx, parcel ->
            val o = byId[idx + 1]
                ?: return@mapIndexed ParcelResult(parcel, Status.NOT_FOUND, emptyList(), "La IA no devolvió este nombre")
            val note = o.optString("note").trim()
            val confidence = o.optString("confidence", "high").lowercase()
            val refs = ArrayList<FloorRef>()
            o.optJSONArray("matches")?.let { m ->
                for (i in 0 until m.length()) {
                    val mo = m.optJSONObject(i) ?: continue
                    refs.add(FloorRef(floorFrom(mo), mo.optString("panel_text"), false))
                }
            }
            val floors = refs.map { it.floor }.distinct()
            when {
                refs.isEmpty() ->
                    ParcelResult(parcel, Status.NOT_FOUND, emptyList(), note.ifBlank { "No aparece en el timbre" })
                floors.size > 1 -> {
                    val list = refs.joinToString(" · ") { "${it.floor?.label ?: "piso ?"} («${it.panelText}»)" }
                    ParcelResult(parcel, Status.AMBIGUOUS, refs, "Varios posibles: $list")
                }
                confidence != "high" || floors.first() == null || note.isNotBlank() -> {
                    val extra = buildList {
                        if (confidence != "high") add("confianza $confidence")
                        if (note.isNotBlank()) add(note)
                        if (floors.first() == null) add("no se ve el piso")
                    }.joinToString(". ")
                    ParcelResult(parcel, Status.WARN, refs, extra)
                }
                else -> ParcelResult(parcel, Status.OK, refs, "")
            }
        }
        return results to panel
    }

    private fun friendlyError(code: Int, body: String): String {
        val detail = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        val base = when (code) {
            401 -> "Clave de API no válida"
            403 -> "La clave no tiene permiso"
            404 -> "Modelo no encontrado (revisa el nombre en Ajustes)"
            429 -> "Demasiadas peticiones o sin saldo en la API"
            529, 503 -> "El servicio está saturado, prueba otra vez"
            else -> "Error $code"
        }
        return if (detail.isNullOrBlank()) base else "$base: $detail"
    }

    /** Llama a la API y devuelve el texto de la respuesta. */
    suspend fun call(apiKey: String, model: String, jpegs: List<ByteArray>, prompt: String): String =
        withContext(Dispatchers.IO) {
            val content = JSONArray()
            for (bytes in jpegs) {
                content.put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/jpeg")
                                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                        ),
                )
            }
            content.put(JSONObject().put("type", "text").put("text", prompt))
            val body = JSONObject()
                .put("model", model.trim())
                .put("max_tokens", 8000)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

            val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = 180_000
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", apiKey.trim())
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw IOException(friendlyError(code, text))

                val blocks = JSONObject(text).getJSONArray("content")
                buildString {
                    for (i in 0 until blocks.length()) {
                        val b = blocks.getJSONObject(i)
                        if (b.optString("type") == "text") append(b.optString("text"))
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
}
