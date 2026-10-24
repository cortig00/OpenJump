package com.openjump.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Full-resource contracts for Polish, Japanese and Korean; Android execution is separate. */
class AdditionalLanguageResourcesTest {
    private val locales = listOf("pl", "ja", "ko")

    private fun file(path: String): File = listOf(File(path), File("app/$path"))
        .firstOrNull { it.isFile } ?: error("Missing resource file: $path")

    private fun catalog(locale: String?): Map<String, Element> {
        val directory = locale?.let { "values-$it" } ?: "values"
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val root = factory.newDocumentBuilder().parse(file("src/main/res/$directory/strings.xml")).documentElement
        val result = linkedMapOf<String, Element>()
        for (index in 0 until root.childNodes.length) {
            val node = root.childNodes.item(index) as? Element ?: continue
            val key = "${node.tagName}/${node.getAttribute("name")}"
            assertTrue("Duplicate $directory/$key", result.put(key, node) == null)
        }
        return result
    }

    private fun items(plural: Element): Map<String, Element> {
        val nodes = plural.getElementsByTagName("item")
        val result = linkedMapOf<String, Element>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as Element
            assertTrue("Duplicate quantity in ${plural.getAttribute("name")}", result.put(node.getAttribute("quantity"), node) == null)
        }
        return result
    }

    private val format = Regex("""%(?:(\d+)\$)?([-#+ 0,(<]*)(\d*)(?:\.(\d+))?([tT]?[a-zA-Z%])""")

    private fun arguments(value: String): List<String> {
        var next = 1
        return format.findAll(value).map { match ->
            val conversion = match.groupValues[5]
            val index = if (conversion == "%" || conversion == "n") 0
                else match.groupValues[1].toIntOrNull() ?: next++
            listOf(index.toString(), match.groupValues[2], match.groupValues[3], match.groupValues[4], conversion).joinToString(":")
        }.sorted().toList()
    }

    private fun markup(node: Node): String = buildString {
        for (index in 0 until node.childNodes.length) {
            val child = node.childNodes.item(index) as? Element ?: continue
            append('<').append(child.tagName).append('>')
            append(markup(child))
            append("</").append(child.tagName).append('>')
        }
    }

    private fun assertText(locale: String, key: String, reference: Element, translated: Element) {
        val text = translated.textContent
        assertTrue("$locale/$key is empty", text.isNotBlank())
        assertEquals("$locale/$key inline markup", markup(reference), markup(translated))
        assertEquals("$locale/$key formatted flag", reference.getAttribute("formatted"), translated.getAttribute("formatted"))
        assertEquals(
            "$locale/$key Android escape sequences",
            Regex("""\\[ntr]""").findAll(reference.textContent).map { it.value }.sorted().toList(),
            Regex("""\\[ntr]""").findAll(text).map { it.value }.sorted().toList(),
        )
        if (translated.getAttribute("formatted") == "false") {
            assertEquals("$locale/$key literal percent signs", reference.textContent.count { it == '%' }, text.count { it == '%' })
            return
        }
        assertEquals("$locale/$key format arguments", arguments(reference.textContent), arguments(text))
        val matches = format.findAll(text).filter { it.groupValues[5] !in listOf("%", "n") }.toList()
        if (matches.isEmpty()) return
        var sequential = 1
        val indexed = matches.map { (it.groupValues[1].toIntOrNull() ?: sequential++) to it.groupValues[5] }
        val args = Array<Any>(indexed.maxOf { it.first }) { "sample" }
        indexed.forEach { (index, conversion) ->
            args[index - 1] = when (conversion) {
                "d", "o", "x", "X" -> 2L
                "f", "e", "E", "g", "G", "a", "A" -> 2.5
                else -> "sample"
            }
        }
        // An invalid format must throw rather than merely matching the placeholder signature.
        String.format(Locale.forLanguageTag(locale), text, *args)
    }

    @Test
    fun `all three languages contain the full base catalog without duplicates`() {
        val base = catalog(null)
        assertTrue("Unexpectedly truncated base catalog", base.size > 1600)
        locales.forEach { locale -> assertEquals("$locale resource keys", base.keys, catalog(locale).keys) }
    }

    @Test
    fun `plurals follow each language rather than copying spanish rules`() {
        locales.forEach { locale ->
            val expected = if (locale == "pl") setOf("one", "few", "many", "other") else setOf("other")
            catalog(locale).filterKeys { it.startsWith("plurals/") }.forEach { (key, plural) ->
                assertEquals("$locale/$key categories", expected, items(plural).keys)
            }
        }
    }

    @Test
    fun `translations preserve formatting contracts and can format real arguments`() {
        val base = catalog(null)
        locales.forEach { locale ->
            catalog(locale).forEach { (key, translated) ->
                val reference = base.getValue(key)
                if (translated.tagName == "plurals") {
                    val originals = items(reference)
                    items(translated).forEach { (quantity, item) ->
                        assertText(locale, "$key/$quantity", originals[quantity] ?: originals.getValue("other"), item)
                    }
                } else {
                    assertText(locale, key, reference, translated)
                }
            }
        }
    }

    @Test
    fun `native labels are present in every catalog and new filters are packaged`() {
        val expected = mapOf(
            "string/settings_language_polish" to "Polski",
            "string/settings_language_japanese" to "日本語",
            "string/settings_language_korean" to "한국어",
        )
        val existing = listOf<String?>(null, "es", "en", "fr", "de", "it", "tr", "pt-rBR", "pt-rPT", "b+es+419")
        (existing + locales).forEach { locale ->
            val values = catalog(locale)
            expected.forEach { (key, label) -> assertEquals("$locale/$key", label, values.getValue(key).textContent) }
        }
        val build = file("build.gradle.kts").readText()
        val filters = Regex("""localeFilters\s*\+=\s*listOf\(([^)]+)\)""").find(build)?.groupValues?.get(1)
            ?: error("Missing localeFilters")
        locales.forEach { assertTrue("Missing package filter $it", filters.contains("\"$it\"")) }
    }

    @Test
    fun `metric translations preserve concentric and eccentric muscle phase meanings`() {
        val japanese = catalog("ja")
        val korean = catalog("ko")
        assertTrue(japanese.getValue("string/encoder_metric_eccentric_time").textContent.contains("遠心"))
        assertTrue(japanese.values.none { it.textContent.contains("偏心") })
        assertTrue(korean.getValue("string/encoder_metric_mcv").textContent.contains("구심성"))
        assertTrue(korean.values.none { it.textContent.contains("추진 속도") })
        listOf("guidance_cmj_1", "guidance_sj_1").forEach { key ->
            assertTrue(korean.getValue("string/$key").textContent.contains("손을 허리에"))
        }
        assertTrue(catalog("pl").getValue("string/formula_loss_spoken").textContent.contains("sto razy różnica"))
    }

    @Test
    fun `new catalogs have native navigation and retain scientific warning keys`() {
        val settings = mapOf("pl" to "Ustawienia", "ja" to "設定", "ko" to "설정")
        val base = catalog(null)
        locales.forEach { locale ->
            val values = catalog(locale)
            assertEquals(settings.getValue(locale), values.getValue("string/nav_settings").textContent)
            listOf("settings_jump_mark_start_title", "labs_live_warning_body", "result_estimates_caveat", "fvp_estimate_notice_body", "privacy_data_body").forEach { key ->
                val text = values.getValue("string/$key").textContent
                assertTrue("$locale/$key must be translated", text != base.getValue("string/$key").textContent)
            }
        }
    }
}
