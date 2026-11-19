package com.openjump.app.data.backup

import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2RootEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM de la proyeccion pura filas V2 -> seccion `angleV2` del manifiesto
 * ([mediaBackupAngleV2Snapshot] / [mediaBackupImagesOf]).
 *
 * No tocan Room, ni ficheros, ni Android: verifican las reglas que la proyeccion promete para que
 * la validacion del manifiesto no tenga que reparar nada (identidades, orden, dueno `null`, asignado
 * frente a reportado, visibilidad y una entrada de imagen por fotograma sin rutas).
 */
class MediaBackupSnapshotTest {

    @Test
    fun projectsRootsFramesAndMarksFieldByFieldKeepingAssignedAndReportedSeparate() {
        val section = mediaBackupAngleV2Snapshot(
            roots = listOf(root(id = 11L, athleteId = 1L, revision = 3)),
            frames = listOf(
                frame(id = 101L, ordinal = 0, imageKey = KEY_A, assignedFrameIndex = 10, reportedFrameIndex = 12),
                frame(id = 102L, ordinal = 1, imageKey = KEY_B, assignedFrameIndex = 20, reportedFrameIndex = null),
            ),
            marks = listOf(
                mark(id = 201L, frameId = 101L, ordinal = 0, visible = true),
                mark(id = 202L, frameId = 101L, ordinal = 1, visible = false),
                mark(id = 203L, frameId = 102L, ordinal = 0, visible = true),
            ),
        )

        val root = section.roots.single()
        assertEquals(11L, root.id)
        assertEquals("ANGLE_MULTI_FRAME_V1", root.analysisFormat)
        assertEquals("session-11", root.sessionKey)
        assertEquals(1L, root.athleteId)
        assertEquals(3, root.revision)

        assertEquals(listOf(101L, 102L), section.frames.map { it.id })
        assertEquals(listOf(0, 1), section.frames.map { it.ordinal })
        val reported = section.frames[0]
        assertEquals(10, reported.assignedFrameIndex)
        assertEquals(10_000L, reported.assignedPtsUs)
        assertEquals(12, reported.reportedFrameIndex)
        assertEquals(12_000L, reported.reportedPtsUs)
        // Lo no verificado se queda `null`: nunca se copia del asignado.
        val unverified = section.frames[1]
        assertEquals(20, unverified.assignedFrameIndex)
        assertNull(unverified.reportedFrameIndex)
        assertEquals(20_000L, unverified.assignedPtsUs)
        assertNull(unverified.reportedPtsUs)

        assertEquals(listOf(201L, 202L, 203L), section.marks.map { it.id })
        assertEquals(listOf(101L, 101L, 102L), section.marks.map { it.frameId })
        assertEquals(listOf(0, 1, 0), section.marks.map { it.ordinal })
        assertEquals(listOf(true, false, true), section.marks.map { it.visible })
        assertEquals("rodilla", section.marks.first().name)
        assertEquals(0x3366CC, section.marks.first().colorArgb)
        assertEquals(1.0, section.marks.first().pointAX, 0.0)
        assertEquals(1918.0, section.marks.first().pointCX, 0.0)
    }

    @Test
    fun generatesExactlyOneImagePerFrameInCanonicalKeyOrderRegardlessOfRowOrder() {
        val frames = listOf(
            frame(id = 102L, ordinal = 1, imageKey = KEY_A, byteCount = 4_096L, sha256 = SHA_A),
            frame(id = 101L, ordinal = 0, imageKey = KEY_B, byteCount = 8_192L, sha256 = SHA_B),
        )

        val images = mediaBackupImagesOf(frames)

        assertEquals(frames.size, images.size)
        // Orden canonico de clave (el mismo que usa el plan de archivo), no el de lectura.
        assertEquals(listOf(KEY_A, KEY_B), images.map { it.imageKey })
        assertEquals(listOf(4_096L, 8_192L), images.map { it.byteCount })
        assertEquals(listOf(SHA_A, SHA_B), images.map { it.sha256 })
        assertEquals(images, mediaBackupImagesOf(frames.reversed()))
        assertTrue(images.all { it.imageKey.matches(Regex("^[0-9a-f]{32}$")) })
    }

    @Test
    fun preservesNullOwnerAndHiddenMarksWithoutInventingAnAthleteOrOverwritingVisibility() {
        val section = mediaBackupAngleV2Snapshot(
            roots = listOf(root(id = 12L, athleteId = null)),
            frames = listOf(frame(id = 111L, ordinal = 0, imageKey = KEY_A)),
            marks = listOf(mark(id = 211L, frameId = 111L, ordinal = 0, visible = false)),
        )

        assertNull(section.roots.single().athleteId)
        assertFalse(section.marks.single().visible)
        assertEquals(1, section.images.size)
        assertEquals(KEY_A, section.images.single().imageKey)
    }

    @Test
    fun imageEntriesAreDerivedFromCanonicalKeysWithoutAnyPathField() {
        val images = mediaBackupImagesOf(listOf(frame(id = 101L, ordinal = 0, imageKey = KEY_A)))

        val entryName = MediaBackupArchive.imageEntryName(images.single().imageKey)

        assertEquals("images/$KEY_A.png", entryName)
        assertEquals(KEY_A, MediaBackupArchive.imageKeyOfEntry(entryName))
        assertTrue(MediaBackupArchive.isReservedEntryName(entryName))
        // La proyeccion no puede expresar una ruta: la clave es la unica identidad del medio.
        assertFalse(entryName.contains(".."))
    }

    @Test
    fun emptyAggregateProjectsToAnEmptySection() {
        val section = mediaBackupAngleV2Snapshot(emptyList(), emptyList(), emptyList())

        assertTrue(section.roots.isEmpty())
        assertTrue(section.frames.isEmpty())
        assertTrue(section.marks.isEmpty())
        assertTrue(section.images.isEmpty())
    }

    private fun root(
        id: Long,
        athleteId: Long?,
        revision: Int = 0,
    ) = AngleAnalysisV2RootEntity(
        id = id,
        sessionKey = "session-$id",
        athleteId = athleteId,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        revision = revision,
    )

    private fun frame(
        id: Long,
        ordinal: Int,
        imageKey: String,
        assignedFrameIndex: Int = 10,
        reportedFrameIndex: Int? = null,
        byteCount: Long = 4_096L,
        sha256: String = SHA_A,
    ) = AngleAnalysisV2FrameEntity(
        id = id,
        rootId = 11L,
        ordinal = ordinal,
        assignedFrameIndex = assignedFrameIndex,
        reportedFrameIndex = reportedFrameIndex,
        assignedPtsUs = assignedFrameIndex * 1_000L,
        reportedPtsUs = reportedFrameIndex?.times(1_000L),
        encodedWidth = 1920,
        encodedHeight = 1080,
        presentationWidth = 1920,
        presentationHeight = 1080,
        rotationDegrees = 0,
        geometryVersion = 1,
        imageKey = imageKey,
        imageWidth = 640,
        imageHeight = 360,
        imageByteCount = byteCount,
        imageSha256 = sha256,
        imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
    )

    private fun mark(
        id: Long,
        frameId: Long,
        ordinal: Int,
        visible: Boolean,
    ) = AngleAnalysisV2MarkEntity(
        id = id,
        frameId = frameId,
        ordinal = ordinal,
        name = "rodilla",
        colorArgb = 0x3366CC,
        visible = visible,
        pointAX = 1.0,
        pointAY = 1.0,
        pointBX = 960.0,
        pointBY = 540.0,
        pointCX = 1918.0,
        pointCY = 1.0,
    )

    private companion object {
        const val KEY_A = "00112233445566778899aabbccddeeff"
        const val KEY_B = "ffeeddccbbaa99887766554433221100"
        const val SHA_A = "00000000000000000000000000000000000000000000000000000000000000aa"
        const val SHA_B = "00000000000000000000000000000000000000000000000000000000000000bb"
    }
}
