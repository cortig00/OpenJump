package com.openjump.app.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Strict, bounded codec for the manual backup contract. */
object ManualBackupCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(document: ManualBackupDocument): ByteArray {
        // Bound caller-owned documents before validate() builds indexes and sorted copies.
        ManualBackupPreflight.checkDocument(document)
        val validated = document.validate()
        val output = BoundedBackupOutputStream(MANUAL_BACKUP_MAX_BYTES)
        json.encodeToStream(ManualBackupDocument.serializer(), validated.deterministic(), output)
        return output.toByteArray()
    }

    fun encodeTo(document: ManualBackupDocument, output: java.io.OutputStream) {
        val bytes = encode(document)
        output.write(bytes)
    }

    fun decode(bytes: ByteArray): ManualBackupDocument {
        require(bytes.size.toLong() <= MANUAL_BACKUP_MAX_BYTES) { "Manual backup is too large" }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Manual backup is not valid UTF-8", error)
        }
        return decode(text)
    }

    fun decode(input: InputStream, declaredLength: Long? = null): ManualBackupDocument {
        // A declared SAF length above MAX fails with "too large"; a negative declared
        // length keeps "Invalid manual backup length" and stays corrupt below.
        require(declaredLength == null || declaredLength >= 0L) { "Invalid manual backup length" }
        require(declaredLength == null || declaredLength <= MANUAL_BACKUP_MAX_BYTES) { "Manual backup is too large" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MANUAL_BACKUP_MAX_BYTES) { "Manual backup is too large" }
            output.write(buffer, 0, count)
        }
        return decode(output.toByteArray())
    }

    fun decode(jsonText: String): ManualBackupDocument {
        ManualBackupPreflight.check(jsonText)
        return json.decodeFromString(ManualBackupDocument.serializer(), jsonText).validate()
    }

    private fun ManualBackupDocument.deterministic() = copy(
        tables = tables.copy(
            athletes = tables.athletes.sortedBy { it.id },
            athleteGroups = tables.athleteGroups.sortedBy { it.id },
            athleteGroupCrossRef = tables.athleteGroupCrossRef.sortedWith(compareBy({ it.groupId }, { it.athleteId })),
            testingSessions = tables.testingSessions.sortedBy { it.id },
            testingParticipants = tables.testingParticipants.sortedWith(compareBy({ it.sessionId }, { it.ordinal }, { it.athleteId })),
            assessments = tables.assessments.sortedBy { it.id },
            attempts = tables.attempts.sortedWith(compareBy({ it.assessmentId }, { it.ordinal }, { it.id })),
            attemptEvents = tables.attemptEvents.sortedWith(compareBy({ it.attemptId }, { it.type }, { it.ordinal })),
            attemptMetrics = tables.attemptMetrics.sortedWith(compareBy({ it.attemptId }, { it.ordinal }, { it.key })),
            attemptCalibrations = tables.attemptCalibrations.sortedBy { it.attemptId },
            attemptSpatialMarks = tables.attemptSpatialMarks.sortedWith(compareBy({ it.attemptId }, { it.type })),
            encoderSessions = tables.encoderSessions.sortedBy { it.id },
            encoderSamples = tables.encoderSamples.sortedWith(compareBy({ it.sessionId }, { it.ordinal })),
            encoderRepetitions = tables.encoderRepetitions.sortedWith(compareBy({ it.sessionId }, { it.ordinal }, { it.id })),
            encoderRepMetrics = tables.encoderRepMetrics.sortedWith(compareBy({ it.repetitionId }, { it.key })),
        ),
    )
}

private class BoundedBackupOutputStream(private val maxBytes: Long) : OutputStream() {
    private val bytes = ByteArrayOutputStream()
    private var written = 0L

    override fun write(value: Int) {
        require(written < maxBytes) { "Manual backup is too large" }
        bytes.write(value)
        written++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(length >= 0 && written <= maxBytes - length) { "Manual backup is too large" }
        bytes.write(buffer, offset, length)
        written += length
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}
