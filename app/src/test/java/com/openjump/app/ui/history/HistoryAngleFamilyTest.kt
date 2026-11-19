package com.openjump.app.ui.history

import com.openjump.app.data.RecentEncoderSession
import com.openjump.app.data.RecentMeasurement
import com.openjump.app.testsupport.ResourceCatalogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM de la familia Ángulos dentro del historial EXISTENTE.
 *
 * Verifica lo que no necesita Android: el reparto de familias en el ViewModel
 * ([historyIncludesAngles]), el orden compartido con el cursor keyset
 * ([mergeMeasurementHistory]), que las filas de ángulos se listan aunque su vídeo o su dueño no
 * permitan abrir el editor, y que las claves nuevas existen en los 13 catálogos con las mismas
 * cantidades de plural que el catálogo base (leídos una sola vez por el snapshot [ResourceCatalogs]).
 *
 * No ejecuta SQL ni composición: la página keyset de ángulos vive en `JumpDao.angleHistoryPage` y
 * se valida con instrumentación (fuera de este slice).
 */
class HistoryAngleFamilyTest {

    // --- reparto de familias ----------------------------------------------------------------------

    @Test
    fun `angles participate in all and angle families`() {
        assertTrue(historyIncludesAngles(HistoryFilters()))
        assertTrue(historyIncludesAngles(HistoryFilters(family = HistoryFamily.ANGLE)))
    }

    @Test
    fun `angles never participate in jump or encoder families`() {
        assertFalse(historyIncludesAngles(HistoryFilters(family = HistoryFamily.JUMP)))
        assertFalse(historyIncludesAngles(HistoryFilters(family = HistoryFamily.ENCODER)))
    }

    @Test
    fun `angles are excluded when a series or metric filter cannot be satisfied by an analysis`() {
        // Un análisis de ángulos no tiene protocolo, ejercicio ni métrica primaria: no puede
        // cumplir estos filtros, así que se excluye en vez de ignorarlos.
        assertFalse(historyIncludesAngles(HistoryFilters(family = HistoryFamily.ANGLE, protocolOrExercise = "CMJ")))
        assertFalse(historyIncludesAngles(HistoryFilters(family = HistoryFamily.ANGLE, metricMin = 10.0)))
        assertFalse(historyIncludesAngles(HistoryFilters(family = HistoryFamily.ANGLE, metricMax = 10.0)))
    }

    @Test
    fun `angles stay listed with athlete group and date filters`() {
        // Filtros que un análisis SÍ puede cumplir no lo excluyen.
        assertTrue(
            historyIncludesAngles(
                HistoryFilters(
                    family = HistoryFamily.ANGLE,
                    athleteId = 3L,
                    groupId = 1L,
                    dateFrom = 10L,
                    dateTo = 20L,
                    search = "ana",
                ),
            ),
        )
        assertTrue(historyIncludesAngles(HistoryFilters(unassignedOnly = true)))
    }

    // --- orden compartido -------------------------------------------------------------------------

    @Test
    fun `angle rows are interleaved by date with the other families`() {
        val merged = mergeMeasurementHistory(
            jumps = listOf(jump(1L, 300L)),
            encoderSessions = listOf(encoder(2L, 100L)),
            angleAnalyses = listOf(angle(3L, 200L)),
            limit = 30,
        )
        assertEquals(listOf(300L, 200L, 100L), merged.map { it.dateTime })
        assertEquals(listOf("JUMP", "ANGLE", "ENCODER"), merged.map { it.kindKey() })
    }

    @Test
    fun `angle rows are last on exact timestamp and id ties so the keyset boundary stays valid`() {
        val merged = mergeMeasurementHistory(
            jumps = listOf(jump(5L, 100L)),
            encoderSessions = listOf(encoder(5L, 100L)),
            angleAnalyses = listOf(angle(5L, 100L)),
            limit = 30,
        )
        assertEquals(listOf("ENCODER", "JUMP", "ANGLE"), merged.map { it.kindKey() })
        // El cursor de la última fila emitida es el que consume la página siguiente.
        assertEquals("ANGLE", merged.last().kindKey())
    }

    @Test
    fun `angle rows respect the page bound`() {
        val angles = (1L..50L).map { angle(it, 500L - it) }
        assertEquals(30, mergeMeasurementHistory(emptyList(), emptyList(), 30, angles).size)
    }

    // --- consulta sin vídeo ---------------------------------------------------------------------

    @Test
    fun `an analysis without video or athlete stays listed`() {
        // El acceso al resultado no depende del vídeo: la fila se lista siempre y siempre navega al
        // detalle guardado, que lee la raíz persistida. Ningún dato de la fila bloquea el listado.
        val noVideo = angle(1L, 10L).copy(videoUri = null, athleteId = null)
        val blankVideo = angle(2L, 9L).copy(videoUri = "   ", athleteId = 0L)
        val merged = mergeMeasurementHistory(
            jumps = emptyList(),
            encoderSessions = emptyList(),
            angleAnalyses = listOf(noVideo, blankVideo),
            limit = 30,
        )
        assertEquals(listOf(1L, 2L), merged.map { it.id })
        assertTrue(merged.all { it is MeasurementHistoryItem.Angle })
    }

    // --- catálogos --------------------------------------------------------------------------------

    @Test
    fun `angle history keys exist once and non empty in all thirteen catalogs`() {
        assertEquals("Se esperaban 13 catálogos", 13, LOCALES.size)
        assertEquals("cada catálogo se declara una sola vez", LOCALES.size, catalogs.size)
        for (catalog in catalogs.ordered) {
            val locale = catalog.label
            val file = catalog.file
            assertTrue(
                "[$locale] falta history_family_angle",
                catalog.value("history_family_angle")?.isNotBlank() == true,
            )
            assertTrue(
                "[$locale] falta history_type_angle",
                catalog.value("history_type_angle")?.isNotBlank() == true,
            )
            // Cada catálogo conserva SU juego de cantidades (ja/ko sólo `other`, pl one/few/many/other):
            // la paridad exigible es con el plural ya establecido en ese mismo catálogo.
            val reference = catalog.pluralItems("history_valid_repetitions").keys
            assertTrue("[$locale] $file: falta el plural de referencia", reference.isNotEmpty())
            val items = catalog.pluralItems("history_angle_count")
            assertEquals(
                "[$locale] $file: history_angle_count ausente o con otras cantidades",
                reference,
                items.keys,
            )
            items.forEach { (quantity, value) ->
                assertTrue("[$locale] $file: history_angle_count '$quantity' vacío", value.isNotBlank())
                assertTrue(
                    "[$locale] $file: history_angle_count '$quantity' pierde el argumento %d",
                    value.contains("%d"),
                )
            }
        }
    }

    private fun jump(id: Long, dateTime: Long) = RecentMeasurement(
        id = id,
        dateTime = dateTime,
        protocolId = "CMJ",
        primaryMetricKey = "HEIGHT_CM",
        primaryMetricValue = 42.0,
        primaryMetricUnit = "CENTIMETER",
        side = null,
    )

    private fun encoder(id: Long, dateTime: Long) = RecentEncoderSession(
        id = id,
        dateTime = dateTime,
        exercise = "SQUAT",
        loadKg = 60.0,
        validRepetitions = 3,
        bestMcv = 0.6,
    )

    private fun angle(id: Long, dateTime: Long) = AngleAnalysisHistoryEntry(
        id = id,
        dateTime = dateTime,
        athleteId = 1L,
        athleteName = "Ana",
        markCount = 2,
        videoUri = "content://video/1",
    )

    private companion object {
        val LOCALES = listOf(
            "es" to "src/main/res/values/strings.xml",
            "es-419" to "src/main/res/values-b+es+419/strings.xml",
            "de" to "src/main/res/values-de/strings.xml",
            "en" to "src/main/res/values-en/strings.xml",
            "es-ES" to "src/main/res/values-es/strings.xml",
            "fr" to "src/main/res/values-fr/strings.xml",
            "it" to "src/main/res/values-it/strings.xml",
            "ja" to "src/main/res/values-ja/strings.xml",
            "ko" to "src/main/res/values-ko/strings.xml",
            "pl" to "src/main/res/values-pl/strings.xml",
            "pt-BR" to "src/main/res/values-pt-rBR/strings.xml",
            "pt-PT" to "src/main/res/values-pt-rPT/strings.xml",
            "tr" to "src/main/res/values-tr/strings.xml",
        )

        /**
         * Snapshot único de la suite: cada fichero se parsea una vez y ningún otro test puede
         * enmascarar un cambio de recursos con una caché global.
         */
        val catalogs: ResourceCatalogs by lazy { ResourceCatalogs.load(LOCALES.toMap()) }
    }
}
