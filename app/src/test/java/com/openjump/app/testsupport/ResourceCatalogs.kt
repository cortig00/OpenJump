package com.openjump.app.testsupport

import java.io.File
import java.util.Collections
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Catálogo `strings.xml` ya parseado, inmutable, para los guards JVM de i18n.
 *
 * El helper es JVM puro (sin Android) y no lee código de producción: la referencia esperada de
 * cualquier comparación sigue siendo el catálogo base que declara cada test. Tampoco modifica,
 * normaliza ni reescribe ningún recurso: expone lo que el fichero declara.
 */
class ResourceCatalog internal constructor(
    /** Etiqueta que el test usa en sus mensajes (`es`, `values-en`, `pt-BR`, ...). */
    val label: String,
    /** Ruta declarada por el test, tal cual, para mensajes y trazabilidad. */
    val path: String,
    /** Fichero realmente resuelto y parseado. */
    val file: File,
    private val stringsByName: Map<String, List<String>>,
    private val pluralsByName: Map<String, Map<String, String>>,
) {

    /** Nombres de `<string>` distintos del fichero (una clave repetida cuenta una vez). */
    val stringCount: Int get() = stringsByName.size

    /** Nombres de `<string>` que empiezan por [prefix], con todos sus valores en orden. */
    fun namesWithPrefix(prefix: String): Map<String, List<String>> =
        names { it.startsWith(prefix) }

    /** Nombres de `<string>` que no empiezan por [prefix], con todos sus valores en orden. */
    fun namesWithoutPrefix(prefix: String): Map<String, List<String>> =
        names { !it.startsWith(prefix) }

    /** Valores declarados para [name] en orden de aparición; vacío si la clave no existe. */
    fun values(name: String): List<String> = stringsByName[name] ?: emptyList()

    /**
     * Último valor declarado para [name], o `null` si no existe. Conserva la semántica `Map` de
     * los guards previos: con una clave repetida gana la última aparición.
     */
    fun value(name: String): String? = stringsByName[name]?.lastOrNull()

    /**
     * Único valor declarado para [name]. Falla con contexto si falta o si aparece más de una vez:
     * es el equivalente explícito de `single()`, para no perder la detección de duplicados.
     */
    fun onlyValue(name: String): String =
        stringsByName[name]?.singleOrNull() ?: throw AssertionError(
            "[$label] '$name' debe aparecer exactamente una vez en $path " +
                "(${file.absolutePath}); apariciones: ${values(name).size}",
        )

    /**
     * Ítems `<item quantity="...">` del plural [name] en orden de aparición. Si el nombre se
     * declarara más de una vez gana la PRIMERA declaración (como el guard original); un nombre
     * ausente devuelve un mapa vacío.
     */
    fun pluralItems(name: String): Map<String, String> = pluralsByName[name] ?: emptyMap()

    private fun names(predicate: (String) -> Boolean): Map<String, List<String>> =
        Collections.unmodifiableMap(stringsByName.filterKeys(predicate))
}

/**
 * Snapshot inmutable de un conjunto de catálogos para UNA suite.
 *
 * Cada fichero se parsea una sola vez por snapshot ([parsedFileCount] lo hace comprobable) y si
 * dos etiquetas declaran el mismo fichero comparten la misma lectura. No hay caché global ni
 * estática: cada test construye su snapshot (`by lazy` en su companion), así que un cambio de
 * recursos nunca queda enmascarado por otra suite ni por una ejecución anterior.
 */
class ResourceCatalogs private constructor(
    private val catalogs: List<ResourceCatalog>,
    /** Ficheros distintos realmente parseados al construir este snapshot. */
    val parsedFileCount: Int,
) {

    /** Número de catálogos declarados (no de ficheros distintos). */
    val size: Int get() = catalogs.size

    /** Catálogos en el orden de declaración del test. */
    val ordered: List<ResourceCatalog> get() = catalogs

    /** Etiquetas declaradas, en orden. */
    val labels: List<String> get() = catalogs.map { it.label }

    /** Catálogo declarado con [label]; falla con contexto si el test no lo declaró. */
    fun catalog(label: String): ResourceCatalog =
        catalogs.firstOrNull { it.label == label }
            ?: throw AssertionError("El snapshot no contiene el locale '$label'. Declarados: $labels")

    companion object {
        /** Niveles de directorio hacia arriba que se prueban al resolver una ruta declarada. */
        private const val MAX_PARENT_LEVELS = 4

        /**
         * URI estándar de la propiedad JAXP `accessExternalDTD` (mismo valor que
         * `XMLConstants.ACCESS_EXTERNAL_DTD`). Se declara como literal porque el classpath de
         * compilación de Android no expone las constantes JAXP de acceso externo, aunque la JVM
         * que ejecuta el test sí entienda la propiedad.
         */
        private const val ACCESS_EXTERNAL_DTD_PROPERTY =
            "http://javax.xml.XMLConstants/property/accessExternalDTD"

        /** URI estándar de la propiedad JAXP `accessExternalSchema` (ver [ACCESS_EXTERNAL_DTD_PROPERTY]). */
        private const val ACCESS_EXTERNAL_SCHEMA_PROPERTY =
            "http://javax.xml.XMLConstants/property/accessExternalSchema"

        /**
         * Resuelve, parsea y devuelve el snapshot inmutable de [locales] (etiqueta -> ruta).
         *
         * La ruta declarada puede ser relativa a la raíz del repositorio
         * (`app/src/main/res/values/strings.xml`) o al módulo `app`
         * (`src/main/res/values/strings.xml`): se prueban ambas formas desde [workingDirectory] y
         * desde sus antecesores, así el test funciona con el cwd de la raíz o de `app`. Si ninguna
         * existe, el error incluye la ruta declarada, el cwd y todas las rutas probadas; si el XML
         * no se puede parsear, el error incluye la ruta declarada, el fichero resuelto y la causa.
         *
         * Se conserva el orden de iteración de [locales].
         */
        fun load(locales: Map<String, String>, workingDirectory: File = File(".")): ResourceCatalogs {
            val parsedByFile = LinkedHashMap<String, Parsed>()
            val catalogs = locales.map { (label, path) ->
                if (label.isBlank()) {
                    throw AssertionError("Locale sin etiqueta en la declaración de catálogos: '$label' -> '$path'")
                }
                if (path.isBlank()) {
                    throw AssertionError("El locale '$label' no declara ruta de catálogo")
                }
                val file = resolve(path, workingDirectory)
                val parsed = parsedByFile.getOrPut(canonicalPath(file)) { parse(path, file) }
                ResourceCatalog(label, path, file, parsed.strings, parsed.plurals)
            }
            return ResourceCatalogs(Collections.unmodifiableList(catalogs), parsedByFile.size)
        }

        private fun resolve(declaredPath: String, workingDirectory: File): File {
            val normalized = declaredPath.removePrefix("./").removePrefix("app/")
            val tried = LinkedHashSet<File>()
            var directory: File? = workingDirectory.absoluteFile
            var level = 0
            while (directory != null && level <= MAX_PARENT_LEVELS) {
                for (candidate in listOf(File(directory, normalized), File(directory, "app/$normalized"))) {
                    tried += candidate
                    if (candidate.isFile) return candidate
                }
                directory = directory.parentFile
                level += 1
            }
            throw AssertionError(
                "No se encuentra '$declaredPath' (catálogo de strings). Directorio de trabajo: " +
                    "${workingDirectory.absolutePath}. Rutas probadas: " +
                    tried.joinToString { it.absolutePath },
            )
        }

        private fun canonicalPath(file: File): String = try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absoluteFile.normalize().path
        }

        private fun parse(declaredPath: String, file: File): Parsed {
            val document = try {
                hardenedFactory().newDocumentBuilder().parse(file)
            } catch (failure: Exception) {
                // Nunca se pasa por omisión: un fichero ilegible o inválido falla con contexto.
                throw AssertionError(
                    "No se pudo parsear $declaredPath (${file.absolutePath}): ${failure.message}",
                    failure,
                )
            }

            val strings = LinkedHashMap<String, MutableList<String>>()
            val stringNodes = document.getElementsByTagName("string")
            for (index in 0 until stringNodes.length) {
                val element = stringNodes.item(index) as? Element ?: continue
                strings.getOrPut(element.getAttribute("name")) { mutableListOf() }
                    .add(element.textContent ?: "")
            }

            val plurals = LinkedHashMap<String, Map<String, String>>()
            val pluralNodes = document.getElementsByTagName("plurals")
            for (index in 0 until pluralNodes.length) {
                val element = pluralNodes.item(index) as? Element ?: continue
                val name = element.getAttribute("name")
                if (plurals.containsKey(name)) continue
                val items = LinkedHashMap<String, String>()
                val children = element.childNodes
                for (childIndex in 0 until children.length) {
                    val child = children.item(childIndex) as? Element ?: continue
                    if (child.tagName != "item") continue
                    items[child.getAttribute("quantity")] = child.textContent ?: ""
                }
                plurals[name] = items
            }

            return Parsed(
                strings = Collections.unmodifiableMap(
                    strings.mapValues { (_, values) -> Collections.unmodifiableList(values) },
                ),
                plurals = Collections.unmodifiableMap(
                    plurals.mapValues { (_, items) -> Collections.unmodifiableMap(items) },
                ),
            )
        }

        /**
         * Parser endurecido: sin DTD, sin entidades externas y sin expansión de entidades. Si el
         * parser de la JVM no admite algún endurecimiento se falla explícitamente en vez de
         * parsear sin él (un guard silenciosamente desprotegido no protege nada).
         */
        private fun hardenedFactory(): DocumentBuilderFactory {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
            val features = listOf(
                XMLConstants.FEATURE_SECURE_PROCESSING to true,
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            )
            for ((name, value) in features) {
                try {
                    factory.setFeature(name, value)
                } catch (failure: Exception) {
                    throw AssertionError(
                        "El parser XML de la JVM no admite el endurecimiento '$name=$value'; " +
                            "no se parsea ningún catálogo sin él.",
                        failure,
                    )
                }
            }
            for (name in listOf(ACCESS_EXTERNAL_DTD_PROPERTY, ACCESS_EXTERNAL_SCHEMA_PROPERTY)) {
                try {
                    factory.setAttribute(name, "")
                } catch (failure: Exception) {
                    throw AssertionError(
                        "El parser XML de la JVM no admite el endurecimiento '$name=\"\"'; " +
                            "no se parsea ningún catálogo sin él.",
                        failure,
                    )
                }
            }
            return factory
        }

        private data class Parsed(
            val strings: Map<String, List<String>>,
            val plurals: Map<String, Map<String, String>>,
        )
    }
}

/**
 * Índices y conversiones de los placeholders de [value], ordenados por índice (misma semántica que
 * el guard de F–V–P): admite índices explícitos (`%1$s`) y secuenciales (`%s`), ignora `%%` y sólo
 * avanza el índice secuencial cuando el placeholder no era explícito.
 */
fun formatArguments(value: String): List<Pair<Int, Char>> {
    val pattern = Regex("%(\\d+\\$)?([a-zA-Z%])")
    val result = mutableListOf<Pair<Int, Char>>()
    var sequential = 1
    for (match in pattern.findAll(value)) {
        val conversion = match.groupValues[2].single()
        if (conversion == '%') continue
        val explicit = match.groupValues[1]
        val index = explicit.removeSuffix("$").toIntOrNull() ?: sequential
        result += index to conversion
        if (explicit.isEmpty()) sequential += 1
    }
    return result.sortedBy { it.first }
}

/**
 * Placeholders posicionales (`%N$s` / `%N$d`) de [value], tal como los compara el guard del editor
 * de ángulos: sólo índices explícitos y conversiones de texto o entero. `%%` y un `%d` sin índice
 * no forman parte del mapa.
 */
fun positionalPlaceholders(value: String): Map<Int, Char> =
    POSITIONAL_PLACEHOLDER.findAll(value).associate { match ->
        match.groupValues[1].toInt() to match.groupValues[2].single()
    }

/** Deshace los escapes de Android que no entiende `String.format` de la JVM. */
fun androidUnescapeForFormat(value: String): String = value.replace("\\'", "'")

private val POSITIONAL_PLACEHOLDER = Regex("%(\\d+)\\$([sd])")
