package com.openjump.app.data.backup

/**
 * Limites del preflight del manifiesto con imagenes.
 *
 * El documento legacy anidado conserva **sus** limites: [MediaBackupManifest.validate] invoca
 * [ManualBackupDocument.validate], que aplica [ManualBackupPreflight] (8 MiB, 1.5 M valores, 50 000
 * items, 4096 unidades por cadena, 1 M de texto y 16 niveles). Los limites de aqui son los del
 * envoltorio mas la seccion de medios, y se justifican como el presupuesto legacy mas el coste de
 * esa seccion (claves de 32 hex, SHA-256 de 64 hex, geometria de fotograma, nombres de marca):
 *  - bytes: 16 MiB, porque el manifiesto contiene el JSON legacy completo;
 *  - valores/items: el doble del presupuesto legacy, suficiente para el envoltorio y las filas V2 y
 *    todavia finito;
 *  - cadenas: por cadena se conserva el mismo tope del contrato de nombres
 *    ([MANUAL_BACKUP_MAX_STRING_CHARS]) y el agregado se dobla junto con los valores;
 *  - profundidad: dos niveles mas (objeto del manifiesto y objeto `angleV2`) con el mismo criterio
 *    estricto de rechazo.
 *
 * Ningun limite es menor que el del contrato legacy: el preflight nuevo no debilita al existente, y
 * el legacy se sigue validando con el suyo dentro de [MediaBackupManifest.validate].
 */
internal data class MediaBackupPreflightLimits(
    val maxBytes: Long = MEDIA_BACKUP_MAX_MANIFEST_BYTES,
    val maxJsonValues: Int = MANUAL_BACKUP_MAX_JSON_VALUES * 2,
    val maxArrayItems: Int = MANUAL_BACKUP_MAX_ARRAY_ITEMS * 2,
    val maxStringChars: Int = MANUAL_BACKUP_MAX_STRING_CHARS,
    val maxTotalStringChars: Long = MANUAL_BACKUP_MAX_TOTAL_STRING_CHARS * 2,
    val maxNumberChars: Int = MANUAL_BACKUP_MAX_NUMBER_CHARS,
    val maxDepth: Int = MANUAL_BACKUP_MAX_NESTING + 2,
    val maxImages: Int = MEDIA_BACKUP_MAX_IMAGES,
    val maxImageBytes: Long = MEDIA_BACKUP_MAX_IMAGE_BYTES,
    val maxTotalImageBytes: Long = MEDIA_BACKUP_MAX_TOTAL_IMAGE_BYTES,
)

/**
 * Presupuestos del codec del manifiesto, inyectables **solo** desde pruebas del propio modulo.
 *
 * La API publica ([MediaBackupManifestCodec.encode], `decode`, `encodeTo`) usa siempre
 * [MediaBackupCodecLimits] por defecto, de modo que ningun llamador de produccion (UI, repositorio,
 * contenedor ZIP) puede subir ni bajar estos topes: la visibilidad `internal` y los valores por
 * defecto iguales a las constantes de contrato ([MEDIA_BACKUP_MAX_MANIFEST_BYTES],
 * [MANUAL_BACKUP_MAX_BYTES]) son la garantia. Se inyectan en pruebas para poder provocar los
 * limites con documentos pequenos en vez de construir 8/16 MiB reales.
 *
 * [maxLegacyBytes] no sustituye al limite del codec v1: [ManualBackupCodec.encode] sigue aplicando
 * su propio tope de [MANUAL_BACKUP_MAX_BYTES] al serializar el documento legacy, y este presupuesto
 * se comprueba ademas sobre los bytes que ese codec produce. Inyectar un valor menor acota, nunca
 * relaja.
 */
internal data class MediaBackupCodecLimits(
    val maxManifestBytes: Long = MEDIA_BACKUP_MAX_MANIFEST_BYTES,
    val maxLegacyBytes: Long = MANUAL_BACKUP_MAX_BYTES,
    val preflight: MediaBackupPreflightLimits = MediaBackupPreflightLimits(),
) {
    /**
     * Limites lexicos efectivos: el presupuesto de bytes del preflight nunca supera el de emision,
     * para que un limite inyectado menor se aplique tambien al texto de entrada.
     */
    val preflightLimits: MediaBackupPreflightLimits
        get() = if (preflight.maxBytes <= maxManifestBytes) preflight else preflight.copy(maxBytes = maxManifestBytes)
}

/**
 * Preflight global del manifiesto: profundidad, cadenas, items, valores, numeros y bytes, **antes**
 * de que kotlinx.serialization construya el grafo.
 *
 * Se escribe aparte del [ManualBackupPreflight] existente porque el scanner legacy es privado y
 * esta acoplado a su contrato; este lo reutiliza donde puede (los limites y el contrato del nombre
 * de angulo) y mantiene la misma severidad: no es un parser de regex, es un recorrido lexico
 * estricto del JSON (gramatica de numeros, escapes, controles, UTF-8), sin construir un DOM. Se
 * anade una comprobacion que el legacy no tiene y aqui es barata: **claves duplicadas** dentro de
 * un mismo objeto se rechazan, porque con dos `formatVersion` el valor que sobrevive depende del
 * parser y el manifiesto dejaria de ser canonico.
 */
internal object MediaBackupManifestPreflight {

    fun check(text: String, limits: MediaBackupPreflightLimits = MediaBackupPreflightLimits()) {
        require(text.length.toLong() <= limits.maxBytes) { "Media backup manifest is too large" }
        Scanner(text, limits).parse()
        require(utf8Length(text, limits.maxBytes) <= limits.maxBytes) { "Media backup manifest is too large" }
    }

    /**
     * Presupuesto estructural del grafo ya construido, equivalente al
     * [ManualBackupPreflight.checkDocument] legacy pero para el manifiesto: se cuentan filas,
     * campos por fila (via descriptor), cadenas y el total de bytes de imagen declarados. Se aplica
     * **antes** de validar y antes de serializar, para que un documento de origen hostil no pueda
     * reventar la memoria al ordenarlo o al escribir el archivo.
     */
    fun checkManifest(
        manifest: MediaBackupManifest,
        limits: MediaBackupPreflightLimits = MediaBackupPreflightLimits(),
    ) {
        val v2 = manifest.angleV2
        val rowsAndFields = listOf(
            v2.roots.size to MediaBackupRoot.serializer().descriptor.elementsCount,
            v2.frames.size to MediaBackupFrame.serializer().descriptor.elementsCount,
            v2.marks.size to MediaBackupMark.serializer().descriptor.elementsCount,
            v2.images.size to MediaBackupImage.serializer().descriptor.elementsCount,
        )
        val rowCount = rowsAndFields.sumOf { it.first.toLong() }
        require(rowCount <= limits.maxArrayItems) { "Media backup manifest is too large (too many rows)" }
        require(v2.images.size <= limits.maxImages) { "Media backup manifest is too large (too many images)" }
        // Root object + envelope + four collections, then each row object and its fixed fields.
        val jsonValues = 16L + rowsAndFields.sumOf { (rows, fields) -> rows.toLong() * (fields + 1L) }
        require(jsonValues <= limits.maxJsonValues) { "Media backup manifest is too large (too much structure)" }
        val strings = StringBudget(limits)
        strings.add(manifest.contract)
        v2.roots.forEach { strings.add(it.analysisFormat, it.sessionKey) }
        v2.frames.forEach { strings.add(it.imageKey, it.imageSha256, it.imageMimeType) }
        v2.marks.forEach { strings.add(it.name) }
        v2.images.forEach { strings.add(it.imageKey, it.sha256) }
        var totalImageBytes = 0L
        v2.frames.forEach { frame ->
            require(frame.imageByteCount in 1..limits.maxImageBytes) {
                "Media backup image is too large"
            }
        }
        v2.images.forEach { image ->
            require(image.byteCount in 1..limits.maxImageBytes) { "Media backup image is too large" }
            totalImageBytes = try {
                Math.addExact(totalImageBytes, image.byteCount)
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("Media backup image bytes overflow", overflow)
            }
            require(totalImageBytes <= limits.maxTotalImageBytes) { "Media backup images are too large" }
        }
    }

    private class StringBudget(private val limits: MediaBackupPreflightLimits) {
        private var total = 0L
        fun add(vararg values: String?) {
            values.forEach { value ->
                if (value != null) {
                    require(value.length <= limits.maxStringChars) {
                        "Media backup manifest is too large (string limit)"
                    }
                    total += value.length
                    require(total <= limits.maxTotalStringChars) {
                        "Media backup manifest is too large (text budget)"
                    }
                }
            }
        }
    }

    private fun utf8Length(text: String, maxBytes: Long): Long {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char.code <= 0x7f -> bytes++
                char.code <= 0x7ff -> bytes += 2
                Character.isHighSurrogate(char) -> {
                    require(index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        "Media backup manifest contains invalid Unicode"
                    }
                    bytes += 4
                    index++
                }
                Character.isLowSurrogate(char) -> throw IllegalArgumentException(
                    "Media backup manifest contains invalid Unicode",
                )
                else -> bytes += 3
            }
            require(bytes <= maxBytes) { "Media backup manifest is too large" }
            index++
        }
        return bytes
    }

    private class Scanner(private val text: String, private val limits: MediaBackupPreflightLimits) {
        private var position = 0
        private var values = 0
        private var arrayItems = 0
        private var totalStringChars = 0L

        fun parse() {
            whitespace()
            value(0)
            whitespace()
            require(position == text.length) { "Invalid media backup manifest JSON" }
        }

        private fun value(depth: Int) {
            values++
            require(values <= limits.maxJsonValues) {
                "Media backup manifest is too large (too much structure)"
            }
            whitespace()
            require(position < text.length) { "Invalid media backup manifest JSON" }
            when (text[position]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> throw IllegalArgumentException("Invalid media backup manifest JSON")
            }
        }

        private fun objectValue(depth: Int) {
            require(depth <= limits.maxDepth) {
                "Media backup manifest is too large (nesting is too deep)"
            }
            position++
            whitespace()
            if (consume('}')) return
            // Claves duplicadas: con dos `formatVersion`, el valor que sobrevive depende del parser.
            val keys = HashSet<String>()
            while (true) {
                whitespace()
                require(position < text.length && text[position] == '"') {
                    "Invalid media backup manifest JSON"
                }
                val key = string(countTowardAggregate = false)
                require(keys.add(key)) { "Duplicate key in media backup manifest JSON" }
                whitespace()
                require(consume(':')) { "Invalid media backup manifest JSON" }
                value(depth)
                whitespace()
                if (consume('}')) return
                require(consume(',')) { "Invalid media backup manifest JSON" }
            }
        }

        private fun arrayValue(depth: Int) {
            require(depth <= limits.maxDepth) {
                "Media backup manifest is too large (nesting is too deep)"
            }
            position++
            whitespace()
            if (consume(']')) return
            while (true) {
                arrayItems++
                require(arrayItems <= limits.maxArrayItems) {
                    "Media backup manifest is too large (too many rows)"
                }
                value(depth)
                whitespace()
                if (consume(']')) return
                require(consume(',')) { "Invalid media backup manifest JSON" }
            }
        }

        /** Escanea una cadena y devuelve su valor decodificado (las claves necesitan el valor). */
        private fun string(countTowardAggregate: Boolean = true): String {
            require(consume('"'))
            val decoded = StringBuilder()
            var decodedChars = 0
            while (position < text.length) {
                val char = text[position++]
                when {
                    char == '"' -> {
                        if (countTowardAggregate) totalStringChars += decodedChars
                        require(
                            decodedChars <= limits.maxStringChars &&
                                (!countTowardAggregate || totalStringChars <= limits.maxTotalStringChars),
                        ) { "Media backup manifest is too large (text budget)" }
                        return decoded.toString()
                    }
                    char == '\\' -> {
                        require(position < text.length) { "Invalid media backup manifest JSON string" }
                        when (text[position++]) {
                            '"', '\\', '/' -> { decoded.append(text[position - 1]); decodedChars++ }
                            'b' -> { decoded.append('\b'); decodedChars++ }
                            'f' -> { decoded.append('\u000C'); decodedChars++ }
                            'n' -> { decoded.append('\n'); decodedChars++ }
                            'r' -> { decoded.append('\r'); decodedChars++ }
                            't' -> { decoded.append('\t'); decodedChars++ }
                            'u' -> {
                                require(position + 4 <= text.length) {
                                    "Invalid media backup manifest JSON string"
                                }
                                var code = 0
                                repeat(4) {
                                    val digit = text[position++].digitToIntOrNull(16)
                                    require(digit != null) { "Invalid media backup manifest JSON string" }
                                    code = code * 16 + digit
                                }
                                decoded.append(code.toChar())
                                decodedChars++
                            }
                            else -> throw IllegalArgumentException(
                                "Invalid media backup manifest JSON string",
                            )
                        }
                    }
                    char.code < 0x20 -> throw IllegalArgumentException(
                        "Invalid media backup manifest JSON string",
                    )
                    else -> { decoded.append(char); decodedChars++ }
                }
                // Bound escaped and unescaped strings while scanning, not after a huge token.
                require(decodedChars <= limits.maxStringChars) {
                    "Media backup manifest is too large (string limit)"
                }
                require(position <= text.length)
            }
            throw IllegalArgumentException("Invalid media backup manifest JSON string")
        }

        private fun number() {
            val start = position
            consume('-')
            require(position < text.length) { "Invalid media backup manifest number" }
            if (consume('0')) {
                require(position >= text.length || text[position] !in '0'..'9') {
                    "Invalid media backup manifest number"
                }
            } else {
                require(text[position] in '1'..'9') { "Invalid media backup manifest number" }
                digits(start)
            }
            if (consume('.')) {
                require(position < text.length && text[position] in '0'..'9') {
                    "Invalid media backup manifest number"
                }
                digits(start)
            }
            if (position < text.length && text[position] in "eE") {
                position++
                if (position < text.length && text[position] in "+-") position++
                require(position < text.length && text[position] in '0'..'9') {
                    "Invalid media backup manifest number"
                }
                digits(start)
            }
            require(position - start <= limits.maxNumberChars) {
                "Media backup manifest is too large (number limit)"
            }
        }

        private fun digits(start: Int) {
            while (position < text.length && text[position] in '0'..'9') {
                position++
                require(position - start <= limits.maxNumberChars) {
                    "Media backup manifest is too large (number limit)"
                }
            }
        }

        private fun literal(expected: String) {
            require(text.regionMatches(position, expected, 0, expected.length)) {
                "Invalid media backup manifest JSON"
            }
            position += expected.length
        }

        private fun whitespace() {
            while (
                position < text.length &&
                (text[position] == ' ' || text[position] == '\t' || text[position] == '\r' || text[position] == '\n')
            ) {
                position++
            }
        }

        private fun consume(char: Char): Boolean {
            if (position < text.length && text[position] == char) {
                position++
                return true
            }
            return false
        }
    }
}
