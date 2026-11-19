package com.openjump.app.data.backup

import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2RootEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Traduccion pura manifiesto -> filas V2 del restore con imagenes (G3b).
 *
 * Se comprueba campo a campo contra entidades construidas con argumentos nombrados, de forma que un
 * intercambio de columnas (asignado/reportado, ancho/alto, tamano/SHA, nombre/color...) falle aqui y
 * no haga falta descubrirlo en el test instrumentado. Tambien se prueba que la traduccion es **sin
 * perdida** haciendo el camino inverso con la proyeccion de captura: `entity()` no puede normalizar,
 * reordenar ni rellenar nada.
 *
 * El fixture es propio (no reutiliza los valores de otros tests) y usa dimensiones, rotaciones y
 * PTS distintos en cada fotograma para que un cruce de campos sea visible.
 */
class MediaBackupRestoreTest {

    @Test
    fun rootKeepsExactIdentityFormatOwnerTimestampsAndRevision() {
        val rows = manifest().angleV2.restoreRows()
        assertEquals(2, rows.roots.size)
        assertEquals(
            AngleAnalysisV2RootEntity(
                id = 1L,
                analysisFormat = "ANGLE_MULTI_FRAME_V1",
                sessionKey = "session-1",
                athleteId = 1L,
                createdAt = 10L,
                updatedAt = 20L,
                revision = 3,
            ),
            rows.roots[0],
        )
        assertEquals(
            AngleAnalysisV2RootEntity(
                id = 2L,
                analysisFormat = "ANGLE_MULTI_FRAME_V1",
                sessionKey = "session-2",
                athleteId = null,
                createdAt = 30L,
                updatedAt = 40L,
                revision = 0,
            ),
            rows.roots[1],
        )
        // El dueno ausente se conserva como `null`: no se inventa un atleta ni se usa 0.
        assertNull(rows.roots[1].athleteId)
    }

    @Test
    fun frameKeepsAssignedAndReportedValuesAndImageIntegritySeparately() {
        val rows = manifest().angleV2.restoreRows()
        assertEquals(3, rows.frames.size)
        // Fotograma con reportado verificado, sin rotacion.
        assertEquals(
            AngleAnalysisV2FrameEntity(
                id = 11L,
                rootId = 1L,
                ordinal = 0,
                assignedFrameIndex = 5,
                reportedFrameIndex = 7,
                assignedPtsUs = 500_000L,
                reportedPtsUs = 700_000L,
                encodedWidth = 640,
                encodedHeight = 480,
                presentationWidth = 640,
                presentationHeight = 480,
                rotationDegrees = 0,
                geometryVersion = 2,
                imageKey = KEY_A,
                imageWidth = 640,
                imageHeight = 480,
                imageByteCount = 4_096L,
                imageSha256 = SHA_A,
                imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
            ),
            rows.frames[0],
        )
        // Fotograma sin verificar y con los ejes intercambiados por la rotacion de 90 grados.
        assertEquals(
            AngleAnalysisV2FrameEntity(
                id = 12L,
                rootId = 2L,
                ordinal = 0,
                assignedFrameIndex = 9,
                reportedFrameIndex = null,
                assignedPtsUs = 900_000L,
                reportedPtsUs = null,
                encodedWidth = 1920,
                encodedHeight = 1080,
                presentationWidth = 1080,
                presentationHeight = 1920,
                rotationDegrees = 90,
                geometryVersion = 1,
                imageKey = KEY_B,
                imageWidth = 540,
                imageHeight = 960,
                imageByteCount = 8_192L,
                imageSha256 = SHA_B,
                imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
            ),
            rows.frames[1],
        )
        assertNull("lo no verificado no se copia del asignado", rows.frames[1].reportedFrameIndex)
        assertNull(rows.frames[1].reportedPtsUs)
        // Tercer fotograma: PTS asignado y reportado distintos de los del resto.
        assertEquals(1_100_000L, rows.frames[2].assignedPtsUs)
        assertEquals(1_200_000L, rows.frames[2].reportedPtsUs)
        assertEquals(12, rows.frames[2].reportedFrameIndex)
        assertEquals(KEY_C, rows.frames[2].imageKey)
        assertEquals(1_024L, rows.frames[2].imageByteCount)
        assertEquals(SHA_C, rows.frames[2].imageSha256)
    }

    @Test
    fun markKeepsNameColorVisibilityOrdinalsAndPoints() {
        val source = manifest().angleV2.marks[1]
        assertEquals(
            AngleAnalysisV2MarkEntity(
                id = 102L,
                frameId = 11L,
                ordinal = 1,
                name = "cadera",
                colorArgb = 0x7F102030,
                visible = false,
                pointAX = 1.0,
                pointAY = 2.0,
                pointBX = 3.0,
                pointBY = 4.0,
                pointCX = 6.0,
                pointCY = 1.0,
            ),
            source.entity(),
        )
        // Ni el color oscuro ni la visibilidad `false` se normalizan al valor por defecto de columna.
        assertEquals(0x7F102030, source.entity().colorArgb)
        assertEquals(false, source.entity().visible)
        // Coordenadas fraccionarias: se copian tal cual, sin redondear ni cuantizar.
        val fractional = manifest().angleV2.marks[2]
        assertEquals(100.5, fractional.entity().pointAX, 0.0)
        assertEquals(200.25, fractional.entity().pointAY, 0.0)
        assertEquals(600.0, fractional.entity().pointCY, 0.0)
    }

    @Test
    fun translationPreservesManifestOrderAndDoesNotRenameIdentities() {
        val angleV2 = manifest().angleV2
        val rows = angleV2.restoreRows()
        assertEquals(angleV2.roots.map { it.id }, rows.roots.map { it.id })
        assertEquals(angleV2.frames.map { it.id }, rows.frames.map { it.id })
        assertEquals(angleV2.marks.map { it.id }, rows.marks.map { it.id })
        assertEquals(angleV2.frames.map { it.ordinal }, rows.frames.map { it.ordinal })
        assertEquals(angleV2.marks.map { it.ordinal }, rows.marks.map { it.ordinal })
        // Los ids de padre tampoco cambian al traducir: la jerarquia se conserva tal cual.
        assertEquals(angleV2.frames.map { it.rootId }, rows.frames.map { it.rootId })
        assertEquals(angleV2.marks.map { it.frameId }, rows.marks.map { it.frameId })
        assertEquals(angleV2.roots.map { it.sessionKey }, rows.roots.map { it.sessionKey })
    }

    @Test
    fun emptyAngleV2ProducesEmptyRowsAndZeroCounts() {
        val empty = manifest(roots = emptyList(), frames = emptyList(), marks = emptyList(), images = emptyList())
        val rows = empty.angleV2.restoreRows()
        assertEquals(emptyList<AngleAnalysisV2RootEntity>(), rows.roots)
        assertEquals(emptyList<AngleAnalysisV2FrameEntity>(), rows.frames)
        assertEquals(emptyList<AngleAnalysisV2MarkEntity>(), rows.marks)
        assertEquals(AngleAnalysisV2RowCounts(roots = 0, frames = 0, marks = 0), rows.counts)
    }

    @Test
    fun countsReportWhatTheManifestDeclares() {
        assertEquals(AngleAnalysisV2RowCounts(roots = 2, frames = 3, marks = 4), manifest().angleV2.restoreRows().counts)
    }

    /**
     * Camino inverso con la proyeccion de captura ([mediaBackupAngleV2Snapshot], la que usa
     * `snapshotWithAngleMedia`): traducir a filas y volver a proyectar devuelve **el mismo**
     * agregado. Si `entity()` perdiera, reordenara o normalizara un campo, este test falla sin
     * necesidad de Room. Las imagenes se comparan en el orden canonico de clave que fija la captura.
     */
    @Test
    fun entityTranslationIsLosslessWhenProjectedBack() {
        val angleV2 = manifest().angleV2
        val rows = angleV2.restoreRows()
        val projected = mediaBackupAngleV2Snapshot(rows.roots, rows.frames, rows.marks)
        assertEquals(angleV2.roots, projected.roots)
        assertEquals(angleV2.frames, projected.frames)
        assertEquals(angleV2.marks, projected.marks)
        assertEquals(angleV2.images.sortedBy { it.imageKey }, projected.images)
        assertEquals(3, projected.images.size)
    }

    /** La validacion del manifiesto es del DAO, no de la traduccion: aqui no se repara nada. */
    @Test
    fun translationDoesNotValidateOrRepairAnAlreadyInvalidPayload() {
        val angleV2 = manifest().angleV2
        val duplicated = angleV2.copy(
            marks = angleV2.marks + angleV2.marks.first().copy(id = 999L, ordinal = 1),
        )
        val rows = duplicated.restoreRows()
        assertEquals(5, rows.marks.size)
        // El ordinal duplicado se traduce tal cual: no se renumera para "arreglarlo".
        assertEquals(listOf(0, 1, 0, 0, 1), rows.marks.map { it.ordinal })
    }

    // --- Fixture ---------------------------------------------------------------------------------

    private fun manifest(
        roots: List<MediaBackupRoot> = defaultRoots(),
        frames: List<MediaBackupFrame> = defaultFrames(),
        marks: List<MediaBackupMark> = defaultMarks(),
        images: List<MediaBackupImage> = defaultImages(),
    ) = MediaBackupManifest(
        contract = MEDIA_BACKUP_CONTRACT,
        formatVersion = MEDIA_BACKUP_FORMAT_VERSION,
        sourceRoomSchemaVersion = MEDIA_BACKUP_ROOM_VERSION,
        createdAtEpochMillis = 1_700_000_000_000L,
        legacy = MediaBackupManifestCodecTest.legacyDocument(),
        angleV2 = MediaBackupAngleV2(roots = roots, frames = frames, marks = marks, images = images),
    ).validate()

    private fun defaultRoots() = listOf(
        MediaBackupRoot(
            id = 1L,
            analysisFormat = "ANGLE_MULTI_FRAME_V1",
            sessionKey = "session-1",
            athleteId = 1L,
            createdAt = 10L,
            updatedAt = 20L,
            revision = 3,
        ),
        MediaBackupRoot(
            id = 2L,
            analysisFormat = "ANGLE_MULTI_FRAME_V1",
            sessionKey = "session-2",
            athleteId = null,
            createdAt = 30L,
            updatedAt = 40L,
            revision = 0,
        ),
    )

    private fun defaultFrames() = listOf(
        MediaBackupFrame(
            id = 11L,
            rootId = 1L,
            ordinal = 0,
            assignedFrameIndex = 5,
            reportedFrameIndex = 7,
            assignedPtsUs = 500_000L,
            reportedPtsUs = 700_000L,
            encodedWidth = 640,
            encodedHeight = 480,
            presentationWidth = 640,
            presentationHeight = 480,
            rotationDegrees = 0,
            geometryVersion = 2,
            imageKey = KEY_A,
            imageWidth = 640,
            imageHeight = 480,
            imageByteCount = 4_096L,
            imageSha256 = SHA_A,
            imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
        ),
        MediaBackupFrame(
            id = 12L,
            rootId = 2L,
            ordinal = 0,
            assignedFrameIndex = 9,
            reportedFrameIndex = null,
            assignedPtsUs = 900_000L,
            reportedPtsUs = null,
            encodedWidth = 1920,
            encodedHeight = 1080,
            presentationWidth = 1080,
            presentationHeight = 1920,
            rotationDegrees = 90,
            geometryVersion = 1,
            imageKey = KEY_B,
            imageWidth = 540,
            imageHeight = 960,
            imageByteCount = 8_192L,
            imageSha256 = SHA_B,
            imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
        ),
        MediaBackupFrame(
            id = 13L,
            rootId = 2L,
            ordinal = 1,
            assignedFrameIndex = 11,
            reportedFrameIndex = 12,
            assignedPtsUs = 1_100_000L,
            reportedPtsUs = 1_200_000L,
            encodedWidth = 1920,
            encodedHeight = 1080,
            presentationWidth = 1080,
            presentationHeight = 1920,
            rotationDegrees = 90,
            geometryVersion = 1,
            imageKey = KEY_C,
            imageWidth = 1080,
            imageHeight = 1920,
            imageByteCount = 1_024L,
            imageSha256 = SHA_C,
            imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
        ),
    )

    private fun defaultMarks() = listOf(
        MediaBackupMark(
            id = 101L,
            frameId = 11L,
            ordinal = 0,
            name = "rodilla",
            colorArgb = 0xFFFF3366.toInt(),
            visible = true,
            pointAX = 10.0,
            pointAY = 10.0,
            pointBX = 100.0,
            pointBY = 100.0,
            pointCX = 200.0,
            pointCY = 10.0,
        ),
        MediaBackupMark(
            id = 102L,
            frameId = 11L,
            ordinal = 1,
            name = "cadera",
            colorArgb = 0x7F102030,
            visible = false,
            pointAX = 1.0,
            pointAY = 2.0,
            pointBX = 3.0,
            pointBY = 4.0,
            pointCX = 6.0,
            pointCY = 1.0,
        ),
        MediaBackupMark(
            id = 103L,
            frameId = 12L,
            ordinal = 0,
            name = "tobillo",
            colorArgb = 0xFF00FF00.toInt(),
            visible = true,
            pointAX = 100.5,
            pointAY = 200.25,
            pointBX = 300.75,
            pointBY = 400.5,
            pointCX = 500.0,
            pointCY = 600.0,
        ),
        MediaBackupMark(
            id = 104L,
            frameId = 13L,
            ordinal = 0,
            name = "hombro",
            colorArgb = 0xFF0000FF.toInt(),
            visible = true,
            pointAX = 10.0,
            pointAY = 20.0,
            pointBX = 30.0,
            pointBY = 40.0,
            pointCX = 500.0,
            pointCY = 60.0,
        ),
    )

    /** Orden canonico de clave, el mismo que fija [MediaBackupManifest.archivePlan]. */
    private fun defaultImages() = listOf(
        MediaBackupImage(imageKey = KEY_A, byteCount = 4_096L, sha256 = SHA_A),
        MediaBackupImage(imageKey = KEY_C, byteCount = 1_024L, sha256 = SHA_C),
        MediaBackupImage(imageKey = KEY_B, byteCount = 8_192L, sha256 = SHA_B),
    )

    private companion object {
        const val KEY_A = "00112233445566778899aabbccddeeff"
        const val KEY_B = "ffeeddccbbaa99887766554433221100"
        const val KEY_C = "0123456789abcdef0123456789abcdef"
        const val SHA_A = "aa00000000000000000000000000000000000000000000000000000000000000"
        const val SHA_B = "bb00000000000000000000000000000000000000000000000000000000000000"
        const val SHA_C = "cc00000000000000000000000000000000000000000000000000000000000000"
    }
}
