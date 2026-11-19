package com.openjump.app.angles

import com.openjump.app.data.ANGLE_GEOMETRY_VERSION
import com.openjump.app.data.AngleAggregateRules
import com.openjump.app.data.AngleAnalysisDraft
import com.openjump.app.data.AngleMarkDraft
import com.openjump.app.data.NEW_ANGLE_MARK_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regresión JVM (sin emulador) de las reglas puras del agregado de ángulos.
 *
 * Cubre la matriz de validación que [AngleAggregateRules] exige ANTES de escribir: dimensiones y
 * rotación coherentes, frame/PTS/versión, marcas con nombre y ordinales únicos, puntos finitos
 * dentro del frame, geometría medible y edición sin mezclar raíces. La persistencia real se prueba
 * en `AngleDaoInstrumentedTest`.
 */
class AngleAggregateRulesTest {

    private fun p(x: Double, y: Double) = AnglePoint(x, y)

    private fun mark(
        ordinal: Int,
        id: Long = NEW_ANGLE_MARK_ID,
        name: String = "Marca $ordinal",
        visible: Boolean = true,
        a: AnglePoint = p(100.0, 100.0),
        b: AnglePoint = p(200.0, 200.0),
        c: AnglePoint = p(300.0, 100.0),
    ) = AngleMarkDraft(
        id = id,
        ordinal = ordinal,
        name = name,
        colorArgb = 0xFF3366CC.toInt(),
        visible = visible,
        pointA = a,
        pointB = b,
        pointC = c,
    )

    private fun draft(
        sessionKey: String = "angle-session",
        athleteId: Long = 7L,
        marks: List<AngleMarkDraft> = listOf(mark(0), mark(1)),
        encodedWidth: Int = 1920,
        encodedHeight: Int = 1080,
        presentationWidth: Int = 1920,
        presentationHeight: Int = 1080,
        rotation: Int = 0,
        frameIndex: Int = 42,
        sourcePtsUs: Long = 1_000_000L,
        geometryVersion: Int = ANGLE_GEOMETRY_VERSION,
    ) = AngleAnalysisDraft(
        sessionKey = sessionKey,
        athleteId = athleteId,
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        presentationWidth = presentationWidth,
        presentationHeight = presentationHeight,
        rotationDegrees = rotation,
        frameIndex = frameIndex,
        sourcePtsUs = sourcePtsUs,
        geometryVersion = geometryVersion,
        marks = marks,
    )

    private fun assertRejected(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    // ---------- Raíz nueva ----------

    @Test
    fun `un borrador completo y coherente se acepta`() {
        AngleAggregateRules.requireNewAnalysis(draft())
    }

    @Test
    fun `rotacion 90 exige dimensiones de presentacion intercambiadas`() {
        AngleAggregateRules.requireNewAnalysis(
            draft(
                rotation = 90,
                encodedWidth = 1920,
                encodedHeight = 1080,
                presentationWidth = 1080,
                presentationHeight = 1920,
            ),
        )
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(draft(rotation = 90))
        }
    }

    @Test
    fun `rotacion no soportada se rechaza`() {
        assertRejected { AngleAggregateRules.requireNewAnalysis(draft(rotation = 45)) }
        assertRejected { AngleAggregateRules.requireNewAnalysis(draft(rotation = 360)) }
    }

    @Test
    fun `dimensiones no positivas se rechazan`() {
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(draft(encodedWidth = 0))
        }
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(draft(presentationHeight = -1))
        }
    }

    @Test
    fun `frame pts y version se validan`() {
        assertRejected { AngleAggregateRules.requireNewAnalysis(draft(frameIndex = -1)) }
        assertRejected { AngleAggregateRules.requireNewAnalysis(draft(sourcePtsUs = -1L)) }
        assertRejected { AngleAggregateRules.requireNewAnalysis(draft(geometryVersion = 0)) }
        assertRejected { AngleAggregateRules.requireNewAnalysis(draft(geometryVersion = 2)) }
    }

    @Test
    fun `sessionKey y atleta son obligatorios`() {
        assertRejected { AngleAggregateRules.requireNewAnalysis(draft(sessionKey = " ")) }
        assertRejected { AngleAggregateRules.requireNewAnalysis(draft(athleteId = 0L)) }
    }

    @Test
    fun `un guardado nuevo no puede reutilizar ids de otra raiz`() {
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(draft(marks = listOf(mark(0, id = 99L))))
        }
    }

    // ---------- Marcas ----------

    @Test
    fun `hace falta al menos una marca`() {
        assertRejected { AngleAggregateRules.requireNewAnalysis(draft(marks = emptyList())) }
    }

    @Test
    fun `nombres vacios y ordinales invalidos se rechazan`() {
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(draft(marks = listOf(mark(0, name = ""))))
        }
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(draft(marks = listOf(mark(-1))))
        }
    }

    @Test
    fun `ordinales repetidos se rechazan`() {
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(draft(marks = listOf(mark(0), mark(0))))
        }
    }

    @Test
    fun `puntos no finitos se rechazan`() {
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(
                draft(marks = listOf(mark(0, a = p(Double.NaN, 10.0)))),
            )
        }
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(
                draft(marks = listOf(mark(0, c = p(1.0, Double.POSITIVE_INFINITY)))),
            )
        }
    }

    @Test
    fun `puntos fuera del frame se rechazan y el ultimo pixel se acepta`() {
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(
                draft(marks = listOf(mark(0, a = p(1920.0, 10.0)))),
            )
        }
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(
                draft(marks = listOf(mark(0, c = p(10.0, -0.5)))),
            )
        }
        AngleAggregateRules.requireNewAnalysis(
            draft(
                marks = listOf(
                    mark(
                        0,
                        a = p(1919.0, 1079.0),
                        b = p(1919.0, 0.0),
                        c = p(0.0, 1079.0),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `una terna degenerada se rechaza pero A igual a C es un cero legitimo`() {
        assertRejected {
            AngleAggregateRules.requireNewAnalysis(
                draft(
                    marks = listOf(
                        mark(0, a = p(10.0, 10.0), b = p(10.0, 10.0), c = p(20.0, 20.0)),
                    ),
                ),
            )
        }
        AngleAggregateRules.requireNewAnalysis(
            draft(marks = listOf(mark(0, a = p(10.0, 10.0), b = p(20.0, 20.0), c = p(10.0, 10.0)))),
        )
    }

    // ---------- Edición (planUpdate) ----------

    private val persisted = listOf(
        mark(0, id = 11L, name = "Cadera"),
        mark(1, id = 12L, name = "Rodilla"),
        mark(2, id = 13L, name = "Tobillo"),
    )

    @Test
    fun `la edicion conserva lo no tocado y separa updates de altas`() {
        val plan = AngleAggregateRules.planUpdate(
            existingMarks = persisted,
            requestedMarks = listOf(
                mark(1, id = 12L, name = "Rodilla editada"),
                mark(3, name = "Nueva"),
            ),
            removedMarkIds = emptySet(),
            presentationWidth = 1920,
            presentationHeight = 1080,
        )
        assertEquals(listOf(12L), plan.updatedMarks.map { it.id })
        assertEquals(1, plan.newMarks.size)
        assertEquals(setOf(11L, 12L, 13L, NEW_ANGLE_MARK_ID), plan.finalMarks.map { it.id }.toSet())
        assertEquals(listOf(0, 1, 2, 3), plan.finalMarks.map { it.ordinal })
        assertEquals("Rodilla editada", plan.finalMarks.single { it.id == 12L }.name)
    }

    @Test
    fun `solo se borran los ids indicados`() {
        val plan = AngleAggregateRules.planUpdate(
            existingMarks = persisted,
            requestedMarks = emptyList(),
            removedMarkIds = setOf(12L),
            presentationWidth = 1920,
            presentationHeight = 1080,
        )
        assertEquals(setOf(12L), plan.removedMarkIds)
        assertEquals(listOf(11L, 13L), plan.finalMarks.map { it.id })
    }

    @Test
    fun `borrar una marca desconocida o borrar todas se rechaza`() {
        assertRejected {
            AngleAggregateRules.planUpdate(
                existingMarks = persisted,
                requestedMarks = emptyList(),
                removedMarkIds = setOf(999L),
                presentationWidth = 1920,
                presentationHeight = 1080,
            )
        }
        assertRejected {
            AngleAggregateRules.planUpdate(
                existingMarks = persisted,
                requestedMarks = emptyList(),
                removedMarkIds = setOf(11L, 12L, 13L),
                presentationWidth = 1920,
                presentationHeight = 1080,
            )
        }
    }

    @Test
    fun `una marca de otra raiz no se puede actualizar`() {
        assertRejected {
            AngleAggregateRules.planUpdate(
                existingMarks = persisted,
                requestedMarks = listOf(mark(0, id = 999L)),
                removedMarkIds = emptySet(),
                presentationWidth = 1920,
                presentationHeight = 1080,
            )
        }
    }

    @Test
    fun `una marca no puede actualizarse y borrarse a la vez`() {
        assertRejected {
            AngleAggregateRules.planUpdate(
                existingMarks = persisted,
                requestedMarks = listOf(mark(0, id = 11L)),
                removedMarkIds = setOf(11L),
                presentationWidth = 1920,
                presentationHeight = 1080,
            )
        }
    }

    @Test
    fun `un id actualizado repetido se rechaza`() {
        assertRejected {
            AngleAggregateRules.planUpdate(
                existingMarks = persisted,
                requestedMarks = listOf(mark(0, id = 11L), mark(1, id = 11L)),
                removedMarkIds = emptySet(),
                presentationWidth = 1920,
                presentationHeight = 1080,
            )
        }
    }

    @Test
    fun `el estado final mantiene ordinales unicos`() {
        assertRejected {
            AngleAggregateRules.planUpdate(
                existingMarks = persisted,
                requestedMarks = listOf(mark(1, id = 11L)),
                removedMarkIds = emptySet(),
                presentationWidth = 1920,
                presentationHeight = 1080,
            )
        }
    }

    @Test
    fun `intercambiar ordinales es un plan valido`() {
        val plan = AngleAggregateRules.planUpdate(
            existingMarks = persisted,
            requestedMarks = listOf(
                mark(1, id = 11L),
                mark(0, id = 12L),
            ),
            removedMarkIds = emptySet(),
            presentationWidth = 1920,
            presentationHeight = 1080,
        )
        assertEquals(listOf(12L, 11L, 13L), plan.finalMarks.map { it.id })
    }

    @Test
    fun `el estado final tambien valida geometria y dimensiones`() {
        assertRejected {
            AngleAggregateRules.planUpdate(
                existingMarks = persisted,
                requestedMarks = listOf(mark(5, id = 11L, a = p(5000.0, 10.0))),
                removedMarkIds = emptySet(),
                presentationWidth = 1920,
                presentationHeight = 1080,
            )
        }
        assertRejected {
            AngleAggregateRules.planUpdate(
                existingMarks = persisted,
                requestedMarks = emptyList(),
                removedMarkIds = emptySet(),
                presentationWidth = 0,
                presentationHeight = 1080,
            )
        }
    }
}
