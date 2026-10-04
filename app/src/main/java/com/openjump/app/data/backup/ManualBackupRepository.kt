package com.openjump.app.data.backup

import java.io.InputStream
import java.io.OutputStream

class ManualBackupRepository(private val dao: ManualBackupDao) {
    suspend fun snapshot(): ManualBackupDocument = dao.snapshot().validate()

    suspend fun create(output: OutputStream): ManualBackupDocument {
        return create(snapshot(), output)
    }

    /** Writes exactly the already captured document, so its preview cannot drift from the file. */
    fun create(document: ManualBackupDocument, output: OutputStream): ManualBackupDocument {
        val validated = document.validate()
        ManualBackupCodec.encodeTo(validated, output)
        return validated
    }

    fun read(input: InputStream, declaredLength: Long? = null): ManualBackupDocument =
        ManualBackupCodec.decode(input, declaredLength)

    fun preview(input: InputStream, declaredLength: Long? = null): ManualBackupSummary =
        ManualBackupSummary.from(read(input, declaredLength))

    suspend fun restore(document: ManualBackupDocument): ManualBackupSummary {
        val valid = document.validate()
        dao.replaceAll(valid)
        return ManualBackupSummary.from(valid)
    }
}
