package com.reparto.pisos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiParseTest {

    private val sample = """
        Aquí tienes:
        {"panel":[{"floor":3,"text":"J. Müller"},{"floor":null,"text":"???"}],
         "results":[
           {"id":1,"matches":[{"floor":3,"panel_text":"J. Müller"}],"confidence":"high","note":""},
           {"id":2,"matches":[],"confidence":"high","note":""},
           {"id":3,"matches":[{"floor":2,"panel_text":"A. Pérez"},{"floor":4,"panel_text":"J. Pérez"}],"confidence":"medium","note":""},
           {"id":4,"matches":[{"floor":1,"panel_text":"Weber"}],"confidence":"low","note":"letra borrosa"}
         ]}
    """.trimIndent()

    @Test
    fun parseaLaRespuestaDeLaIa() {
        val parcels = Matcher.parseParcels("2 Juan Müller\nNadie\nPérez\nWeber\nSin respuesta")
        val (res, panel) = AiClient.toResults(parcels, AiClient.extractJson(sample))

        assertEquals(2, panel.size)
        assertEquals(Floor.number(3), panel[0].floor)
        assertEquals(null, panel[1].floor)

        assertEquals(Status.OK, res[0].status)
        assertEquals(Floor.number(3), res[0].refs.single().floor)
        assertEquals("2 Juan Müller", res[0].parcel.raw)
        assertEquals(Status.NOT_FOUND, res[1].status)
        assertEquals(Status.AMBIGUOUS, res[2].status)
        assertEquals(Status.WARN, res[3].status)
        assertTrue(res[3].note.contains("letra borrosa"))
        assertEquals(Status.NOT_FOUND, res[4].status)
    }

    @Test
    fun promptListaLosNombresSinCantidad() {
        val prompt = AiClient.buildPrompt(Matcher.parseParcels("2 Juan Pérez\nAna López"))
        assertTrue(prompt.contains("1. Juan Pérez"))
        assertTrue(prompt.contains("2. Ana López"))
    }
}
