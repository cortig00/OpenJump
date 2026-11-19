package com.openjump.app.data.angles

import java.security.SecureRandom

/**
 * Claves opacas del almacén privado de imágenes anotadas.
 *
 * Una clave es **datos generados por el aparato**, no un identificador del usuario: 16 bytes de
 * [SecureRandom] en hexadecimal minúscula (32 caracteres). No contiene rutas, separadores, puntos,
 * espacios, nombres de usuario ni texto libre, de modo que no puede expresar `..`, una ruta
 * absoluta ni nada que dependa del sistema de ficheros.
 *
 * La validación es una **allowlist canónica** ([CANONICAL_KEY]): sólo la forma exacta que produce
 * [generate] es aceptable. Rechazar por lista negra (`..`, `/`) sería insuficiente porque dejaría
 * pasar otras formas no canónicas; aquí todo lo que no sea `[0-9a-f]{32}` se rechaza antes de
 * tocar disco.
 *
 * `SecureRandom` se instancia una sola vez y es thread-safe: no se usa `Random` ni la identidad de
 * la sesión, porque una clave predecible permitiría enumerar imágenes privadas.
 */
object AngleImageKey {

    /** Longitud canónica de una clave: 32 caracteres hexadecimales minúscula (16 bytes). */
    const val HEX_LENGTH: Int = 32

    private val CANONICAL_KEY: Regex = Regex("^[0-9a-f]{$HEX_LENGTH}$")

    private val secureRandom: SecureRandom by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecureRandom() }

    /**
     * Genera una clave nueva.
     *
     * **No** comprueba colisiones: 128 bits aleatorios hacen que dos claves coincidan con
     * probabilidad despreciable, y la publicación del almacén nunca sobrescribe una clave
     * existente: si ocurriera, la escritura se rechaza con
     * [AngleImageStoreError.KeyAlreadyExists] en vez de reemplazar datos.
     */
    fun generate(): String {
        val bytes = ByteArray(HEX_LENGTH / 2)
        secureRandom.nextBytes(bytes)
        return bytes.toLowerHexString()
    }

    /** `true` sólo si [key] tiene la forma canónica exacta; ver [CANONICAL_KEY]. */
    fun isCanonical(key: String): Boolean = CANONICAL_KEY.matches(key)
}

private val HEX_DIGITS: CharArray = "0123456789abcdef".toCharArray()

/** Hexadecimal minúscula de [ByteArray]; usado para claves y para el SHA-256 sellado. */
internal fun ByteArray.toLowerHexString(): String {
    val out = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        out[index * 2] = HEX_DIGITS[value ushr 4]
        out[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(out)
}
