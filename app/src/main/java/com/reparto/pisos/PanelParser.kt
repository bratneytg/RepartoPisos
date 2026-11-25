package com.reparto.pisos

import kotlin.math.abs
import kotlin.math.max

/** Una línea de texto reconocida en la foto, con su posición en píxeles. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val cx: Double get() = (left + right) / 2.0
    val cy: Double get() = (top + bottom) / 2.0
}

/**
 * Convierte las líneas del OCR en nombres con piso.
 *  1. Busca rótulos de piso ("EG", "3. OG", "Piso 2", "2º", columna de números 1-2-3…).
 *  2. Asigna cada nombre al rótulo de su misma fila o, si los rótulos son cabeceras, al de encima.
 *  3. Si no hay rótulos, cada fila de la foto se toma como un piso (estimado).
 */
object PanelParser {

    private val EG = Regex("""^(?:eg|e\.g\.|erdgeschoss|erdgeschoß|parterre|pb|p\.b\.|planta baja|bajo|ground floor)$""", RegexOption.IGNORE_CASE)
    private val UG = Regex("""^(?:ug|u\.g\.|kg|k\.g\.|keller|sotano|sótano|untergeschoss|untergeschoß|basement)$""", RegexOption.IGNORE_CASE)
    private val DG = Regex("""^(?:dg|d\.g\.|dachgeschoss|dachgeschoß|atico|ático|penthouse)$""", RegexOption.IGNORE_CASE)
    private val N_THEN = Regex("""^(\d{1,2})\s*\.?\s*(?:og|o\.g\.|obergeschoss|obergeschoß|etage|etg\.?|stock|stockwerk|piso|planta|floor|fl\.?|stg\.?)$""", RegexOption.IGNORE_CASE)
    private val WORD_THEN_N = Regex("""^(?:og|etage|etg\.?|stock|stockwerk|piso|planta|floor|level|ebene)\s*\.?\s*(-?\d{1,2})$""", RegexOption.IGNORE_CASE)
    private val ORDINAL = Regex("""^(\d{1,2})\s*[º°ª]\.?$""")
    private val DOTTED = Regex("""^(\d{1,2})\s*[.:)]$""")

    private val LEAD_N = Regex("""^(\d{1,2})\s*\.?\s*(?:og|o\.g\.|etage|etg\.?|stock|piso|planta)\s+(.*\p{L}.*)$""", RegexOption.IGNORE_CASE)
    private val LEAD_W = Regex("""^(eg|ug|dg|kg)\s+(.*\p{L}.*)$""", RegexOption.IGNORE_CASE)

    private fun fromNumber(s: String): Floor? {
        val n = s.toIntOrNull() ?: return null
        return if (n in -3..60) Floor.number(n) else null
    }

    private fun fromWord(t: String): Floor? = when {
        EG.matches(t) -> Floor.number(0)
        UG.matches(t) -> Floor.number(-1)
        DG.matches(t) -> Floor.ATTIC
        else -> null
    }

    /** Si el texto es *solo* un rótulo de piso, devuelve el piso. */
    fun floorOf(raw: String): Floor? {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        fromWord(t)?.let { return it }
        N_THEN.find(t)?.let { return fromNumber(it.groupValues[1]) }
        WORD_THEN_N.find(t)?.let { return fromNumber(it.groupValues[1]) }
        ORDINAL.find(t)?.let { return fromNumber(it.groupValues[1]) }
        DOTTED.find(t)?.let { return fromNumber(it.groupValues[1]) }
        return null
    }

    /** "2. OG Müller" → (piso 2, "Müller"). */
    fun splitLeading(raw: String): Pair<Floor, String>? {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        LEAD_N.find(t)?.let { m -> fromNumber(m.groupValues[1])?.let { return it to m.groupValues[2] } }
        LEAD_W.find(t)?.let { m -> fromWord(m.groupValues[1])?.let { return it to m.groupValues[2] } }
        return null
    }

    private fun median(v: List<Int>): Double {
        if (v.isEmpty()) return 1.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private val ONLY_NUMBER = Regex("^\\d{1,2}$")

    /** Columna de números sueltos que suben o bajan de uno en uno (1,2,3…): se toman como pisos. */
    private fun numberColumnMarkers(lines: List<OcrLine>): List<Pair<OcrLine, Floor>> {
        val cands = lines.filter { ONLY_NUMBER.matches(it.text.trim()) && it.text.trim().toInt() in 0..60 }
        if (cands.size < 3) return emptyList()
        val h = median(cands.map { it.height })
        val groups = ArrayList<MutableList<OcrLine>>()
        for (c in cands.sortedBy { it.left }) {
            val g = groups.lastOrNull()
            if (g != null && c.left - g.first().left <= 1.5 * h) g.add(c) else groups.add(mutableListOf(c))
        }
        for (g in groups.sortedByDescending { it.size }) {
            if (g.size < 3) break
            val byY = g.sortedBy { it.cy }
            val v = byY.map { it.text.trim().toInt() }
            val inc = v.zipWithNext().all { (a, b) -> b > a }
            val dec = v.zipWithNext().all { (a, b) -> b < a }
            if (inc || dec) return byY.map { it to Floor.number(it.text.trim().toInt()) }
        }
        return emptyList()
    }

    fun build(lines: List<OcrLine>, topIsHighest: Boolean = true, bottomFloor: Int = 0): List<PanelEntry> {
        val clean = lines.filter { it.text.isNotBlank() }

        val direct = ArrayList<PanelEntry>() // líneas tipo "2. OG Müller"
        val rest = ArrayList<OcrLine>()
        for (l in clean) {
            val lead = splitLeading(l.text)
            if (lead != null) direct.add(PanelEntry(lead.second, lead.first, false)) else rest.add(l)
        }

        var markers: List<Pair<OcrLine, Floor>> = rest.mapNotNull { l -> floorOf(l.text)?.let { l to it } }
        if (markers.isEmpty()) markers = numberColumnMarkers(rest)
        val markerLines = markers.map { it.first }
        val names = rest.filter { l -> markerLines.none { it === l } && l.text.count { c -> c.isLetter() } >= 2 }

        val assigned = when {
            names.isEmpty() -> emptyList()
            markers.isNotEmpty() -> assignByMarkers(names, markers)
            direct.isNotEmpty() -> names.map { PanelEntry(it.text, null, false) }
            else -> assignByBands(names, topIsHighest, bottomFloor)
        }
        return direct + assigned
    }

    private fun assignByMarkers(names: List<OcrLine>, markers: List<Pair<OcrLine, Floor>>): List<PanelEntry> =
        names.map { n ->
            val sameRow = markers.filter { (m, _) -> abs(n.cy - m.cy) <= 0.6 * max(m.height, n.height) }
            val pick = if (sameRow.isNotEmpty()) {
                sameRow.minByOrNull { (m, _) -> abs(n.cy - m.cy) }
            } else {
                markers.filter { (m, _) -> m.bottom <= n.top + 0.5 * n.height }
                    .minByOrNull { (m, _) -> (n.top - m.bottom) + 0.3 * abs(n.cx - m.cx) }
            }
            PanelEntry(n.text, pick?.second, false)
        }

    private fun assignByBands(names: List<OcrLine>, topIsHighest: Boolean, bottomFloor: Int): List<PanelEntry> {
        val h = median(names.map { it.height })
        val bands = ArrayList<MutableList<OcrLine>>()
        var center = 0.0
        for (l in names.sortedBy { it.cy }) {
            val b = bands.lastOrNull()
            if (b != null && l.cy - center <= 0.75 * h) {
                b.add(l)
                center = b.map { it.cy }.average()
            } else {
                bands.add(mutableListOf(l))
                center = l.cy
            }
        }
        val n = bands.size
        val out = ArrayList<PanelEntry>()
        bands.forEachIndexed { i, band ->
            val num = if (topIsHighest) bottomFloor + (n - 1 - i) else bottomFloor + i
            val f = Floor.number(num)
            band.sortedBy { it.left }.forEach { out.add(PanelEntry(it.text, f, true)) }
        }
        return out
    }
}
