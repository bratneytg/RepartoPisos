package com.reparto.pisos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {

    private fun entries(vararg e: Pair<String, Int>) = e.map { PanelEntry(it.first, Floor.number(it.second)) }

    private fun floorOf(name: String, panel: List<PanelEntry>): Floor? =
        Matcher.matchAll(Matcher.parseParcels(name), panel).single().refs.firstOrNull()?.floor

    private fun statusOf(name: String, panel: List<PanelEntry>): Status =
        Matcher.matchAll(Matcher.parseParcels(name), panel).single().status

    // ------------------------------------------------------------ cantidades

    @Test
    fun ignoraLaCantidadDelante() {
        val p = Matcher.parseParcels("2 Juan Pérez\n3x Ana López\n(4) Xavier Ruiz\n2 Xavier Ruiz\n5. Hans Meier\nMüller")
        assertEquals(listOf("Juan Pérez", "Ana López", "Xavier Ruiz", "Xavier Ruiz", "Hans Meier", "Müller"), p.map { it.name })
        assertEquals(listOf(2, 3, 4, 2, 5, 1), p.map { it.qty })
        assertEquals("2 Juan Pérez", p[0].raw)
    }

    // ------------------------------------------------------- apellidos / acentos

    @Test
    fun umlautsYAcentos() {
        val panel = entries("Müller" to 1, "García" to 2, "Strauß" to 3)
        assertEquals(Floor.number(1), floorOf("Mueller", panel))
        assertEquals(Floor.number(1), floorOf("Muller", panel))
        assertEquals(Floor.number(2), floorOf("Garcia", panel))
        assertEquals(Floor.number(3), floorOf("Strauss", panel))
    }

    @Test
    fun nombreEnCualquierOrden() {
        val panel = entries("J. Pérez" to 2, "Schmidt" to 4)
        assertEquals(Floor.number(2), floorOf("Juan Pérez", panel))
        assertEquals(Floor.number(2), floorOf("Pérez, Juan", panel))
        assertEquals(Floor.number(2), floorOf("Pérez Juan", panel))
        assertEquals(Floor.number(4), floorOf("2 Hans Schmidt", panel))
    }

    // ------------------------------------------------------- apellidos repetidos

    @Test
    fun apellidoRepetidoDecideLaInicial() {
        val panel = entries("J. Pérez" to 2, "A. Pérez" to 4, "M. Pérez" to 6)
        assertEquals(Floor.number(2), floorOf("Juan Pérez", panel))
        assertEquals(Floor.number(4), floorOf("Ana Pérez", panel))
        assertEquals(Floor.number(6), floorOf("3 María Pérez", panel))
        assertEquals(Status.OK, statusOf("Ana Pérez", panel))
    }

    @Test
    fun apellidoRepetidoSinInicialEsAmbiguo() {
        val panel = entries("J. Pérez" to 2, "A. Pérez" to 4)
        val r = Matcher.matchAll(Matcher.parseParcels("Pérez"), panel).single()
        assertEquals(Status.AMBIGUOUS, r.status)
        assertEquals(setOf(Floor.number(2), Floor.number(4)), r.refs.map { it.floor }.toSet())
    }

    @Test
    fun placaSinInicialGanaSobreInicialQueNoCoincide() {
        val panel = entries("Pérez" to 3, "A. Pérez" to 5)
        assertEquals(Floor.number(3), floorOf("Juan Pérez", panel))
        assertEquals(Floor.number(5), floorOf("Ana Pérez", panel))
    }

    @Test
    fun nombreCompletoGanaAInicial() {
        val panel = entries("Juan Pérez" to 2, "J. Pérez" to 7)
        assertEquals(Floor.number(2), floorOf("Juan Pérez", panel))
    }

    @Test
    fun inicialQueNoCoincideAvisa() {
        val panel = entries("A. Pérez" to 5)
        val r = Matcher.matchAll(Matcher.parseParcels("Juan Pérez"), panel).single()
        assertEquals(Status.WARN, r.status)
        assertEquals(Floor.number(5), r.refs.single().floor)
    }

    @Test
    fun mismoNombreDosPlacasMismoPisoNoEsAmbiguo() {
        val panel = entries("Müller" to 3, "Mueller" to 3)
        assertEquals(Status.OK, statusOf("Müller", panel))
    }

    // ------------------------------------------------------------- falsos positivos

    @Test
    fun mismoNombreDePilaDistintoApellidoNoCoincide() {
        val panel = entries("Ana López" to 3)
        assertEquals(Status.NOT_FOUND, statusOf("Ana García", panel))
    }

    @Test
    fun noEncontrado() {
        val panel = entries("Müller" to 1, "Schmidt" to 2)
        assertEquals(Status.NOT_FOUND, statusOf("Yilmaz", panel))
    }

    // ------------------------------------------------------------------- OCR

    @Test
    fun toleraConfusionesDelOcr() {
        val panel = entries("Mi11er" to 1, "Schm1dt" to 2)
        assertEquals(Floor.number(1), floorOf("Miller", panel))
        assertEquals(Floor.number(2), floorOf("Schmidt", panel))
    }

    @Test
    fun parecidoAvisa() {
        val panel = entries("Schmitt" to 4)
        val r = Matcher.matchAll(Matcher.parseParcels("Schmidt"), panel).single()
        assertEquals(Status.WARN, r.status)
        assertEquals(Floor.number(4), r.refs.single().floor)
    }

    @Test
    fun exactoGanaAParecido() {
        val panel = entries("Schmitt" to 4, "Schmidt" to 6)
        assertEquals(Floor.number(6), floorOf("Schmidt", panel))
        assertEquals(Status.OK, statusOf("Schmidt", panel))
    }

    @Test
    fun placaConVariosNombres() {
        val panel = entries("Weber / Yilmaz" to 5)
        assertEquals(Floor.number(5), floorOf("Yilmaz", panel))
        assertEquals(Floor.number(5), floorOf("Weber", panel))
    }

    @Test
    fun apellidoPrimeroSinComaConInicialEnLaPlaca() {
        val panel = entries("H. Müller" to 3)
        val r = Matcher.matchAll(Matcher.parseParcels("Müller Hans"), panel).single()
        assertEquals(Status.OK, r.status)
        assertEquals(Floor.number(3), r.refs.single().floor)
    }

    @Test
    fun agrupaPorPiso() {
        val panel = entries("Müller" to 3, "Schmidt" to 1, "Weber" to 3)
        val res = Matcher.matchAll(Matcher.parseParcels("Weber\n2 Schmidt\nMüller\nNadie"), panel)
        val g = Matcher.group(res)
        assertEquals(listOf(1, 3), g.byFloor.map { it.first.order })
        assertEquals(listOf("Weber", "Müller"), g.byFloor[1].second.map { it.parcel.raw })
        assertEquals(1, g.notFound.size)
        val text = Matcher.exportText(res)
        assertTrue(text.contains("PISO 3"))
        assertTrue(text.contains("2 Schmidt"))
        assertTrue(text.contains("NO ENCONTRADOS"))
    }

    // ----------------------------------------------------------- rótulos de piso

    @Test
    fun reconoceRotulosDePiso() {
        assertEquals(Floor.number(3), PanelParser.floorOf("3. OG"))
        assertEquals(Floor.number(3), PanelParser.floorOf("3 OG"))
        assertEquals(Floor.number(2), PanelParser.floorOf("OG 2"))
        assertEquals(Floor.number(0), PanelParser.floorOf("EG"))
        assertEquals(Floor.number(0), PanelParser.floorOf("Erdgeschoss"))
        assertEquals(Floor.number(-1), PanelParser.floorOf("UG"))
        assertEquals(Floor.number(4), PanelParser.floorOf("Piso 4"))
        assertEquals(Floor.number(2), PanelParser.floorOf("2º"))
        assertEquals(Floor.number(5), PanelParser.floorOf("5."))
        assertEquals(Floor.ATTIC, PanelParser.floorOf("DG"))
        assertNull(PanelParser.floorOf("Müller"))
        assertNull(PanelParser.floorOf("12"))
    }

    @Test
    fun separaRotuloYNombreEnLaMismaLinea() {
        val r = PanelParser.splitLeading("2. OG Müller")
        assertNotNull(r)
        assertEquals(Floor.number(2), r!!.first)
        assertEquals("Müller", r.second)
        assertNull(PanelParser.splitLeading("Müller"))
    }

    // ------------------------------------------------------ geometría del timbre

    private fun line(text: String, left: Int, top: Int, w: Int = 120, h: Int = 30) =
        OcrLine(text, left, top, left + w, top + h)

    @Test
    fun rotulosALaIzquierdaDeCadaFila() {
        val lines = listOf(
            line("2. OG", 10, 100), line("Müller", 200, 100), line("Schmidt", 400, 102),
            line("1. OG", 10, 200), line("Weber", 200, 201),
            line("EG", 10, 300), line("Yilmaz", 200, 298),
        )
        val e = PanelParser.build(lines).associate { it.text to it.floor }
        assertEquals(Floor.number(2), e["Müller"])
        assertEquals(Floor.number(2), e["Schmidt"])
        assertEquals(Floor.number(1), e["Weber"])
        assertEquals(Floor.number(0), e["Yilmaz"])
    }

    @Test
    fun rotulosComoCabecera() {
        val lines = listOf(
            line("3. OG", 50, 0),
            line("Müller", 50, 40), line("Schmidt", 50, 80),
            line("2. OG", 50, 140),
            line("Weber", 50, 180),
        )
        val e = PanelParser.build(lines).associate { it.text to it.floor }
        assertEquals(Floor.number(3), e["Müller"])
        assertEquals(Floor.number(3), e["Schmidt"])
        assertEquals(Floor.number(2), e["Weber"])
    }

    @Test
    fun columnaDeNumerosSueltos() {
        val lines = listOf(
            line("3", 10, 100, 20), line("Müller", 200, 100),
            line("2", 10, 200, 20), line("Weber", 200, 200),
            line("1", 10, 300, 20), line("Schmidt", 200, 300),
        )
        val e = PanelParser.build(lines).associate { it.text to it.floor }
        assertEquals(Floor.number(3), e["Müller"])
        assertEquals(Floor.number(2), e["Weber"])
        assertEquals(Floor.number(1), e["Schmidt"])
    }

    @Test
    fun sinRotulosSeCuentanLasFilas() {
        val lines = listOf(
            line("Müller", 50, 100), line("Schmidt", 250, 101),
            line("Weber", 50, 200),
            line("Yilmaz", 50, 300),
        )
        val e = PanelParser.build(lines, topIsHighest = true, bottomFloor = 0)
        val m = e.associate { it.text to it.floor }
        assertEquals(Floor.number(2), m["Müller"])
        assertEquals(Floor.number(2), m["Schmidt"])
        assertEquals(Floor.number(1), m["Weber"])
        assertEquals(Floor.number(0), m["Yilmaz"])
        assertTrue(e.all { it.guessed })

        val abajo = PanelParser.build(lines, topIsHighest = false, bottomFloor = 1).associate { it.text to it.floor }
        assertEquals(Floor.number(1), abajo["Müller"])
        assertEquals(Floor.number(3), abajo["Yilmaz"])
    }
}
