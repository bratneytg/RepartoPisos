package com.reparto.pisos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildingsTest {

    private val panel = listOf(
        PanelEntry("J. Pérez", Floor.number(2)),
        PanelEntry("A. Pérez", Floor.number(4)),
        PanelEntry("Müller", Floor.ATTIC),
        PanelEntry("Weber", Floor.number(0), guessed = true),
        PanelEntry("Sin piso", null),
    )

    @Test
    fun jsonIdaYVuelta() {
        val list = listOf(
            Building("Hauptstr. 12", panel, 1_700_000_000_000L, true),
            Building("Bahnhofstr. 3", emptyList(), 5L, false),
        )
        val back = BuildingJson.decode(BuildingJson.encode(list))
        assertEquals(list, back)
    }

    @Test
    fun buscaConLosDatosGuardadosSinFotos() {
        val saved = BuildingJson.decode(BuildingJson.encode(listOf(Building("Hauptstr. 12", panel, 1L, true)))).single()
        val res = Matcher.matchAll(Matcher.parseParcels("2 Juan Pérez\nAna Pérez\nMüller\nNadie"), saved.entries)
        assertEquals(Floor.number(2), res[0].refs.single().floor)
        assertEquals(Floor.number(4), res[1].refs.single().floor)
        assertEquals(Floor.ATTIC, res[2].refs.single().floor)
        assertEquals(Status.NOT_FOUND, res[3].status)
    }

    @Test
    fun almacenGuardaYCarga() {
        val dir = File(System.getProperty("java.io.tmpdir"), "reparto_test_${System.nanoTime()}").apply { mkdirs() }
        val store = BuildingStore(File(dir, "buildings.json"))
        assertTrue(store.load().isEmpty())
        val a = Building("A", panel, 1L, false)
        store.save(listOf(a))
        assertEquals(listOf(a), store.load())
        val b = Building("B", emptyList(), 2L, true)
        store.save(listOf(a, b))
        assertEquals(listOf(a, b), store.load())
        dir.deleteRecursively()
    }
}
