package com.openjump.app.video.angles

/**
 * Identidad **visual** de un fotograma, independiente de cualquier PTS asignado.
 *
 * Dos utilidades puras (sin Android):
 *
 * 1. [digest] calcula una huella FNV-1a de 64 bits sobre los píxeles ARGB. Sirve para acreditar que
 *    la imagen que se muestra y la que se anota son la misma; **no** aporta información temporal.
 * 2. [encodeOrdinalFrame] / [decodeOrdinal] implementan un patrón visual sintético que codifica un
 *    ordinal de 12 bits en una fila de celdas. Solo se usa con vídeos de prueba generados para
 *    verificar la correlación píxel↔frame: el ordinal se lee de los píxeles decodificados, así que un
 *    adaptador que "asigne" el PTS correcto pero devuelva otro fotograma se detecta por contenido, no
 *    por igualdad de PTS.
 *
 * El patrón exige un frame de al menos [REQUIRED_WIDTH] × [REQUIRED_HEIGHT] píxeles. Las celdas se
 * muestrean en su centro (luminancia > [LUMA_THRESHOLD] = 1) para sobrevivir a la compresión H.264
 * 4:2:0 con rango limitado.
 */
object FrameVisualIdentity {

    const val CELL_SIZE = 16
    /** Margen alineado a macrobloque H.264: el centro de cada celda cae dentro de un macrobloque, no en su borde. */
    const val MARGIN = 16
    const val DATA_BITS = 12
    const val MAX_ORDINAL = (1 shl DATA_BITS) - 1
    const val MARKER_CELLS = 3
    const val REQUIRED_WIDTH = MARGIN * 2 + CELL_SIZE * (MARKER_CELLS + DATA_BITS)
    const val REQUIRED_HEIGHT = MARGIN * 2 + CELL_SIZE
    const val LUMA_THRESHOLD = 140

    private const val BACKGROUND = 0xFF606060.toInt()
    private const val DARK = 0xFF000000.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()

    /** Patrón de marcador: oscuro, claro, oscuro. Evita confundir un frame cualquiera con un ordinal. */
    private val MARKER = booleanArrayOf(false, true, false)

    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    /** Huella de los píxeles completos en orden de filas. */
    fun digest(pixels: IntArray): Long {
        var hash = FNV_OFFSET_BASIS
        for (pixel in pixels) {
            hash = (hash xor (pixel.toLong() and 0xFFFF_FFFFL)) * FNV_PRIME
        }
        return hash
    }

    /**
     * Huella de un bitmap leído fila a fila, sin materializar un array de frame completo.
     * [readRow] debe rellenar `row[0 until width]` con los píxeles ARGB de la fila `y`.
     */
    fun digest(width: Int, height: Int, readRow: (y: Int, row: IntArray) -> Unit): Long {
        require(width >= 1 && height >= 1) { "Dimensiones de imagen no válidas." }
        var hash = FNV_OFFSET_BASIS
        val row = IntArray(width)
        for (y in 0 until height) {
            readRow(y, row)
            for (x in 0 until width) {
                hash = (hash xor (row[x].toLong() and 0xFFFF_FFFFL)) * FNV_PRIME
            }
        }
        return hash
    }

    /**
     * Dibuja el frame sintético del [ordinal]: fondo gris, marcador oscuro/claro/oscuro y 12 celdas
     * de datos en orden LSB primero (claro = 1, oscuro = 0). El resto del frame queda en el color de
     * fondo, sin información adicional.
     */
    fun encodeOrdinalFrame(width: Int, height: Int, ordinal: Int): IntArray {
        require(width >= REQUIRED_WIDTH && height >= REQUIRED_HEIGHT) {
            "El frame sintético debe medir al menos ${REQUIRED_WIDTH}×$REQUIRED_HEIGHT."
        }
        require(ordinal in 0..MAX_ORDINAL) { "Ordinal fuera del rango de $DATA_BITS bits." }
        val pixels = IntArray(width * height) { BACKGROUND }
        var cell = 0
        MARKER.forEach { light -> paintCell(pixels, width, cell++, if (light) LIGHT else DARK) }
        for (bit in 0 until DATA_BITS) {
            val light = (ordinal shr bit) and 1 == 1
            paintCell(pixels, width, cell++, if (light) LIGHT else DARK)
        }
        return pixels
    }

    /**
     * Lee el ordinal de los píxeles. Devuelve `null` si el tamaño no alcanza, el marcador no está
     * presente o el frame no es el patrón sintético esperado. Nunca adivina un ordinal.
     */
    fun decodeOrdinal(pixels: IntArray, width: Int, height: Int): Int? {
        if (width < REQUIRED_WIDTH || height < REQUIRED_HEIGHT) return null
        if (pixels.size.toLong() != width.toLong() * height.toLong()) return null
        val centerY = MARGIN + CELL_SIZE / 2
        var cell = 0
        for (expectedLight in MARKER) {
            val actualLight = readCellLight(pixels, width, cell++, centerY)
            if (actualLight != expectedLight) return null
        }
        var ordinal = 0
        for (bit in 0 until DATA_BITS) {
            if (readCellLight(pixels, width, cell++, centerY)) ordinal = ordinal or (1 shl bit)
        }
        return ordinal
    }

    private fun paintCell(pixels: IntArray, width: Int, cell: Int, color: Int) {
        val left = MARGIN + cell * CELL_SIZE
        for (y in MARGIN until MARGIN + CELL_SIZE) {
            val rowOffset = y * width
            for (x in left until left + CELL_SIZE) {
                pixels[rowOffset + x] = color
            }
        }
    }

    private fun readCellLight(pixels: IntArray, width: Int, cell: Int, y: Int): Boolean {
        val x = MARGIN + cell * CELL_SIZE + CELL_SIZE / 2
        return luma(pixels[y * width + x]) > LUMA_THRESHOLD
    }

    private fun luma(color: Int): Int {
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF
        return (77 * red + 150 * green + 29 * blue) shr 8
    }
}
