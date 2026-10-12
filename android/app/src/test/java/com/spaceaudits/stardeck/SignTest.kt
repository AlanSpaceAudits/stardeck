package com.spaceaudits.stardeck

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SignTest {
    // Reference value shared with the PC client (seestar_pc/tests/sign_abc.txt).
    @Test
    fun signMatchesReference() {
        val key = File("src/main/assets/seestar_key.pem").readText()
        val expected = File("../../seestar_pc/tests/sign_abc.txt").readText().trim()
        assertEquals(expected, sign(key, "abc"))
    }
}
