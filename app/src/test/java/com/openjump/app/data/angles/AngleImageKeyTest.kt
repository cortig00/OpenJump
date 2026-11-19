package com.openjump.app.data.angles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validación canónica de claves opacas. No se prueba entropía estadística: se fija la **forma**
 * admitida y que todo lo que pueda expresar una ruta, un nombre de usuario o una variante no
 * canónica se rechace.
 */
class AngleImageKeyTest {

    @Test
    fun `genera claves canonicas y distintas`() {
        val keys = (0 until 1_000).map { AngleImageKey.generate() }

        assertEquals("las claves generadas no deben repetirse", 1_000, keys.toSet().size)
        keys.forEach { key ->
            assertEquals(AngleImageKey.HEX_LENGTH, key.length)
            assertTrue("clave no canónica: $key", AngleImageKey.isCanonical(key))
        }
    }

    @Test
    fun `acepta exactamente la forma hexadecimal minuscula de 32 caracteres`() {
        assertTrue(AngleImageKey.isCanonical("0123456789abcdef0123456789abcdef"))
        assertTrue(AngleImageKey.isCanonical("f".repeat(32)))
        assertTrue(AngleImageKey.isCanonical("0".repeat(32)))
    }

    @Test
    fun `rechaza rutas nombres de usuario y variantes no canonicas`() {
        val rejected = listOf(
            "",
            ".",
            "..",
            "../imagen",
            "../../etc/passwd",
            "/data/user/0/com.openjump.app/files/angle-images/a",
            "a/b",
            "sub/0123456789abcdef0123456789abcdef",
            "cesar",
            "foto del atleta",
            "0123456789ABCDEF0123456789abcdef",
            "0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0",
            "0123456789abcdef0123456789abcdeg",
            " 0123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef ",
            "0123456789abcdef0123456789abcdef\n",
            "image-key-frame-0",
            "\u0000" + "0".repeat(31),
            "0".repeat(31) + "\u00e1",
        )

        rejected.forEach { key ->
            assertFalse("debía rechazarse: [$key]", AngleImageKey.isCanonical(key))
        }
    }
}
