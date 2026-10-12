package com.spaceaudits.stardeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {
    @Test
    fun findsMessierAndCommonNames() {
        val c = Catalog(File("src/main/assets/catalog.tsv").readText())
        assertEquals("NGC 224", c.search("m31", 5)[0].name)
        assertEquals("M 3", c.search("M 3", 5)[0].alias)
        assertEquals("NGC 1976", c.search("orion nebula", 5)[0].name)
        assertTrue(c.search("", 5).isEmpty())
    }

    // Meeus, Astronomical Algorithms, example 21.b (theta Persei, 2028 Nov 13.19 TD).
    @Test
    fun precessionMatchesMeeusExample() {
        val (ra, dec) = precessFromJ2000(2 + 44 / 60.0 + 12.975 / 3600, 49 + 13 / 60.0 + 39.90 / 3600, 2462088.69)
        assertEquals(2 + 46 / 60.0 + 11.331 / 3600, ra, 0.5 / 15 / 3600)
        assertEquals(49 + 20 / 60.0 + 54.54 / 3600, dec, 0.5 / 3600)
    }
}
