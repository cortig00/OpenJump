package com.openjump.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteNormalizerTest {
    @Test fun trimsAndConvertsBlankToNull() {
        assertEquals("ensayo", NoteNormalizer.normalize("  ensayo  "))
        assertNull(NoteNormalizer.normalize(" \n\t "))
        assertNull(normalizeNote(null))
    }

    @Test fun acceptsExactlyFiveHundredAndRejectsLonger() {
        assertEquals(500, NoteNormalizer.normalize("x".repeat(500))!!.length)
        assertThrows(IllegalArgumentException::class.java) { NoteNormalizer.normalize("x".repeat(501)) }
    }
}
