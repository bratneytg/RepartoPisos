package com.reparto.pisos

import java.text.Normalizer
import kotlin.math.max

/** Una línea escrita por el repartidor. [qty] es el número que va delante (se ignora al buscar). */
data class Parcel(val raw: String, val qty: Int, val name: String)

data class Floor(val order: Int, val label: String) {
    companion object {
        fun number(n: Int): Floor = when {
            n == 0 -> Floor(0, "Planta baja (EG)")
            n == -1 -> Floor(-1, "Sótano (UG)")
            n < 0 -> Floor(n, "Sótano ${-n}")
            else -> Floor(n, "Piso $n")
        }

        val ATTIC = Floor(99, "Ático (DG)")
    }
}

/** Un nombre leído en el timbre, con el piso al que pertenece (si se ha podido saber). */
data class PanelEntry(val text: String, val floor: Floor?, val guessed: Boolean = false)

enum class Status { OK, WARN, AMBIGUOUS, NOT_FOUND }

data class FloorRef(val floor: Floor?, val panelText: String, val guessed: Boolean)

data class ParcelResult(
    val parcel: Parcel,
    val status: Status,
    val refs: List<FloorRef>,
    val note: String = "",
)

data class Groups(
    val byFloor: List<Pair<Floor, List<ParcelResult>>>,
    val unknownFloor: List<ParcelResult>,
    val ambiguous: List<ParcelResult>,
    val notFound: List<ParcelResult>,
)

object Matcher {

    // ---------------------------------------------------------------- entrada

    private val QTY = Regex("""^\s*\(?(\d{1,3})\)?\s*(?:[×*]\s*|[xX]\s+|[.:\-–—]\s*)?(?=\p{L})""")

    /** Una línea = un destinatario. Un número delante ("2 Juan Pérez", "3x Ana", "(2) Ana") es la cantidad. */
    fun parseParcels(text: String): List<Parcel> =
        text.split('\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { raw ->
                val m = QTY.find(raw)
                if (m != null) {
                    Parcel(raw, m.groupValues[1].toInt(), raw.substring(m.range.last + 1).trim())
                } else {
                    Parcel(raw, 1, raw)
                }
            }
            .filter { it.name.isNotEmpty() }

    // ---------------------------------------------------------- normalización

    private val MARKS = Regex("\\p{M}+")

    /** Minúsculas, sin acentos; ä/ö/ü → ae/oe/ue y ß → ss. */
    fun fold(s: String): String {
        var t = s.lowercase()
        t = t.replace("ß", "ss").replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("æ", "ae").replace("œ", "oe").replace("ø", "o").replace("ł", "l")
            .replace("đ", "d").replace("ı", "i")
        return Normalizer.normalize(t, Normalizer.Form.NFD).replace(MARKS, "")
    }

    /** Forma para comparar: "Müller", "Mueller" y "Muller" dan lo mismo. */
    fun skeleton(s: String): String =
        fold(s).replace("ae", "a").replace("oe", "o").replace("ue", "u").replace("ss", "s")

    private val STOP = setOf(
        "fam", "familie", "family", "dr", "prof", "herr", "frau", "hr", "fr", "mr", "mrs",
        "sr", "sra", "don", "dona", "von", "van", "de", "del", "la", "der", "den", "dipl",
        "ing", "med", "co", "wg",
    )
    private val SEP = Regex("[^\\p{L}\\p{N}]+")

    /**
     * [words]: palabras de 2+ letras (normalizadas). [singles]: iniciales sueltas ("J." → j).
     * [primary]: índices de [words] que se consideran apellido (la última palabra, o lo anterior a la coma).
     */
    class Tokens(val words: List<String>, val singles: Set<Char>, val primary: Set<Int>)

    fun tokensOf(name: String): Tokens {
        val commaAt = name.indexOf(',')
        val words = ArrayList<String>()
        val singles = HashSet<Char>()
        val primary = HashSet<Int>()

        fun consume(part: String, isPrimaryPart: Boolean) {
            for (tok in part.split(SEP)) {
                if (tok.isEmpty() || tok.none { it.isLetter() }) continue
                if (fold(tok) in STOP) continue
                if (tok.length == 1) {
                    skeleton(tok).firstOrNull()?.let { singles.add(it) }
                    continue
                }
                words.add(skeleton(tok))
                if (isPrimaryPart) primary.add(words.lastIndex)
            }
        }

        if (commaAt >= 0) {
            consume(name.substring(0, commaAt), true)
            consume(name.substring(commaAt + 1), false)
        } else {
            consume(name, false)
        }
        if (primary.isEmpty() && words.isNotEmpty()) primary.add(words.lastIndex)
        return Tokens(words, singles, primary)
    }

    // -------------------------------------------------------------- distancia

    private val CONFUSABLE = listOf("il1|!", "o0", "s5", "b6", "uv", "ce", "z2", "g9")

    private fun subCost(a: Char, b: Char): Double {
        if (a == b) return 0.0
        for (g in CONFUSABLE) if (a in g && b in g) return 0.4
        return 1.0
    }

    /** Levenshtein donde las confusiones típicas del OCR (i/l/1, o/0, s/5…) cuestan poco. */
    fun dist(a: String, b: String): Double {
        val n = a.length
        val m = b.length
        if (n == 0) return m.toDouble()
        if (m == 0) return n.toDouble()
        var prev = DoubleArray(m + 1) { it.toDouble() }
        var cur = DoubleArray(m + 1)
        for (i in 1..n) {
            cur[0] = i.toDouble()
            for (j in 1..m) {
                cur[j] = minOf(prev[j] + 1.0, cur[j - 1] + 1.0, prev[j - 1] + subCost(a[i - 1], b[j - 1]))
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }

    private fun strictLimit(len: Int) = when {
        len <= 3 -> 0.0
        len <= 5 -> 0.5
        len <= 8 -> 0.9
        else -> 1.4
    }

    private fun looseLimit(len: Int) = when {
        len <= 3 -> 0.0
        len <= 5 -> 1.0
        len <= 8 -> 1.4
        else -> 2.0
    }

    // ------------------------------------------------- personas en el timbre

    class Person(val entry: PanelEntry, val shown: String, val tokens: Tokens)

    private val SPLIT = Regex("""\s*[/&+|;]\s*|\s(?:und|y|u\.)\s""", RegexOption.IGNORE_CASE)

    /** Una placa puede traer varios nombres ("Müller / Schmidt"): cada uno es una persona. */
    fun personsOf(entry: PanelEntry): List<Person> =
        entry.text.split(SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Person(entry, it, tokensOf(it)) }
            .filter { it.tokens.words.isNotEmpty() }

    // ------------------------------------------------------------ comparación

    private class Eval(
        val person: Person,
        val score: Double,
        val loose: Boolean,
        val weak: Boolean,
        val initial: Boolean?,
    )

    private fun evaluate(p: Tokens, person: Person): Eval? {
        val pw = person.tokens.words
        val used = BooleanArray(pw.size)
        val matched = BooleanArray(p.words.size)
        var score = 0.0
        var loose = false
        var primaryMatched = false

        // Primero las palabras de apellido, para que elijan antes.
        val order = p.words.indices.sortedBy { if (it in p.primary) 0 else 1 }
        for (i in order) {
            val w = p.words[i]
            val isPrimary = i in p.primary
            var best = -1
            var bestD = Double.MAX_VALUE
            for (j in pw.indices) {
                if (used[j]) continue
                val d = dist(w, pw[j])
                if (d < bestD) {
                    bestD = d
                    best = j
                }
            }
            if (best < 0) continue
            val len = max(w.length, pw[best].length)
            val sim = 1.0 - bestD / len
            if (bestD <= strictLimit(len)) {
                used[best] = true
                matched[i] = true
                score += sim
                if (isPrimary) primaryMatched = true
            } else if (isPrimary && bestD <= looseLimit(len)) {
                used[best] = true
                matched[i] = true
                score += sim * 0.8
                loose = true
                primaryMatched = true
            }
        }
        if (matched.none { it }) return null

        val unmatchedParcel = p.words.indices.filter { !matched[it] }.map { p.words[it] }
        val unmatchedPerson = pw.indices.filter { !used[it] }.map { pw[it] }

        // Coincide solo por el nombre de pila y el apellido del timbre es otro → es otra persona.
        var weak = false
        if (!primaryMatched) {
            if (unmatchedPerson.isNotEmpty()) return null
            weak = true
        }

        val pInit = p.singles + unmatchedParcel.map { it[0] }
        val qInit = person.tokens.singles + unmatchedPerson.map { it[0] }
        val initial: Boolean? = when {
            unmatchedParcel.isEmpty() && p.words.size >= 2 -> true // nombre completo coincide
            pInit.isEmpty() || qInit.isEmpty() -> null
            else -> pInit.any { it in qInit }
        }
        return Eval(person, score, loose, weak && initial != true, initial)
    }

    fun match(parcel: Parcel, persons: List<Person>): ParcelResult {
        val pt = tokensOf(parcel.name)
        if (pt.words.isEmpty()) {
            return ParcelResult(parcel, Status.NOT_FOUND, emptyList(), "No he entendido el nombre")
        }
        val evals = persons.mapNotNull { evaluate(pt, it) }
        if (evals.isEmpty()) {
            return ParcelResult(parcel, Status.NOT_FOUND, emptyList(), "No aparece en el timbre")
        }

        // Preferimos coincidencias exactas; las "parecidas" solo si no hay otra cosa.
        val pool = evals.filter { !it.loose && !it.weak }
            .ifEmpty { evals.filter { !it.loose } }
            .ifEmpty { evals }
        val best = pool.maxOf { it.score }
        val group = pool.filter { it.score >= best - 0.05 }

        // Apellido repetido: decide la inicial del nombre.
        val agree = group.filter { it.initial == true }
        val unknown = group.filter { it.initial == null }
        val conflict = agree.isEmpty() && unknown.isEmpty()
        val chosen = when {
            agree.isNotEmpty() -> agree
            unknown.isNotEmpty() -> unknown
            else -> group
        }

        val refs = chosen
            .map { FloorRef(it.person.entry.floor, it.person.shown, it.person.entry.guessed) }
            .distinctBy { it.floor to it.panelText }
        val floors = refs.map { it.floor }.distinct()

        if (floors.size > 1) {
            val list = refs.joinToString(" · ") { "${it.floor?.label ?: "piso ?"} («${it.panelText}»)" }
            return ParcelResult(parcel, Status.AMBIGUOUS, refs, "Varios posibles: $list")
        }

        val notes = ArrayList<String>()
        val shown = refs.first().panelText
        if (chosen.any { it.loose }) notes.add("Parecido, revisa: el timbre pone «$shown»")
        if (chosen.any { it.weak }) notes.add("Coincide solo una parte del nombre: «$shown»")
        if (conflict) notes.add("La inicial no coincide con «$shown»")
        if (floors.first() == null) notes.add("No he podido saber el piso")
        return ParcelResult(parcel, if (notes.isEmpty()) Status.OK else Status.WARN, refs, notes.joinToString(". "))
    }

    fun matchAll(parcels: List<Parcel>, entries: List<PanelEntry>): List<ParcelResult> {
        val persons = entries.flatMap { personsOf(it) }
        return parcels.map { match(it, persons) }
    }

    // ----------------------------------------------------------- presentación

    fun group(results: List<ParcelResult>): Groups {
        val ok = results.filter { it.status == Status.OK || it.status == Status.WARN }
        val withFloor = ok.filter { it.refs.firstOrNull()?.floor != null }
        val byFloor = withFloor
            .groupBy { it.refs.first().floor!! }
            .entries
            .sortedBy { it.key.order }
            .map { it.key to it.value }
        return Groups(
            byFloor = byFloor,
            unknownFloor = ok.filter { it.refs.firstOrNull()?.floor == null },
            ambiguous = results.filter { it.status == Status.AMBIGUOUS },
            notFound = results.filter { it.status == Status.NOT_FOUND },
        )
    }

    fun exportText(results: List<ParcelResult>): String {
        val g = group(results)
        fun line(r: ParcelResult) = "  ${r.parcel.raw}" + if (r.note.isNotBlank()) "   [!] ${r.note}" else ""
        return buildString {
            for ((floor, items) in g.byFloor) {
                appendLine(floor.label.uppercase())
                items.forEach { appendLine(line(it)) }
                appendLine()
            }
            if (g.unknownFloor.isNotEmpty()) {
                appendLine("PISO DESCONOCIDO")
                g.unknownFloor.forEach { appendLine(line(it)) }
                appendLine()
            }
            if (g.ambiguous.isNotEmpty()) {
                appendLine("REVISAR (VARIOS POSIBLES)")
                g.ambiguous.forEach { appendLine(line(it)) }
                appendLine()
            }
            if (g.notFound.isNotEmpty()) {
                appendLine("NO ENCONTRADOS")
                g.notFound.forEach { appendLine("  ${it.parcel.raw}") }
            }
        }.trimEnd()
    }
}
