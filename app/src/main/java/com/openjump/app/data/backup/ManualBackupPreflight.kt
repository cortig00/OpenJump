package com.openjump.app.data.backup

/** Resource limits applied before kotlinx.serialization creates the backup graph. */
internal data class ManualBackupPreflightLimits(
    val maxBytes: Long = MANUAL_BACKUP_MAX_BYTES,
    val maxJsonValues: Int = MANUAL_BACKUP_MAX_JSON_VALUES,
    val maxArrayItems: Int = MANUAL_BACKUP_MAX_ARRAY_ITEMS,
    val maxStringChars: Int = MANUAL_BACKUP_MAX_STRING_CHARS,
    val maxTotalStringChars: Long = MANUAL_BACKUP_MAX_TOTAL_STRING_CHARS,
    val maxNumberChars: Int = MANUAL_BACKUP_MAX_NUMBER_CHARS,
    val maxDepth: Int = MANUAL_BACKUP_MAX_NESTING,
)

/** A lexical JSON walk: validates structure and budgets without building a JSON DOM. */
internal object ManualBackupPreflight {
    fun check(text: String, limits: ManualBackupPreflightLimits = ManualBackupPreflightLimits()) {
        require(text.length.toLong() <= limits.maxBytes) { "Manual backup is too large" }
        Scanner(text, limits).parse()
        require(utf8Length(text, limits.maxBytes) <= limits.maxBytes) { "Manual backup is too large" }
    }

    fun checkDocument(document: ManualBackupDocument, limits: ManualBackupPreflightLimits = ManualBackupPreflightLimits()) {
        val tables = document.tables
        val tableRowsAndFields = listOf(
            tables.athletes.size to BackupAthlete.serializer().descriptor.elementsCount,
            tables.athleteGroups.size to BackupGroup.serializer().descriptor.elementsCount,
            tables.athleteGroupCrossRef.size to BackupMembership.serializer().descriptor.elementsCount,
            tables.testingSessions.size to BackupTestingSession.serializer().descriptor.elementsCount,
            tables.testingParticipants.size to BackupTestingParticipant.serializer().descriptor.elementsCount,
            tables.assessments.size to BackupAssessment.serializer().descriptor.elementsCount,
            tables.attempts.size to BackupAttempt.serializer().descriptor.elementsCount,
            tables.attemptEvents.size to BackupAttemptEvent.serializer().descriptor.elementsCount,
            tables.attemptMetrics.size to BackupAttemptMetric.serializer().descriptor.elementsCount,
            tables.attemptCalibrations.size to BackupAttemptCalibration.serializer().descriptor.elementsCount,
            tables.attemptSpatialMarks.size to BackupAttemptSpatialMark.serializer().descriptor.elementsCount,
            tables.encoderSessions.size to BackupEncoderSession.serializer().descriptor.elementsCount,
            tables.encoderSamples.size to BackupEncoderSample.serializer().descriptor.elementsCount,
            tables.encoderRepetitions.size to BackupEncoderRepetition.serializer().descriptor.elementsCount,
            tables.encoderRepMetrics.size to BackupEncoderRepMetric.serializer().descriptor.elementsCount,
        )
        val rowCount = tableRowsAndFields.sumOf { it.first.toLong() }
        require(rowCount <= limits.maxArrayItems) { "Manual backup is too large (too many rows)" }
        // Root object + header/table values, then each table-row object and its fixed fields.
        val jsonValues = 23L + tableRowsAndFields.sumOf { (rows, fields) -> rows.toLong() * (fields + 1L) }
        require(jsonValues <= limits.maxJsonValues) { "Manual backup is too large (too much structure)" }
        val strings = StringBudget(limits)
        strings.add(document.contract)
        tables.athletes.forEach { strings.add(it.displayName, it.sex, it.notes, it.avatarKey) }
        tables.athleteGroups.forEach { strings.add(it.name, it.notes) }
        tables.testingSessions.forEach { strings.add(it.groupNameSnapshot, it.family, it.protocolId, it.exercise, it.side, it.status) }
        tables.assessments.forEach { strings.add(it.sessionKey, it.protocolId, it.primaryMetricKey, it.primaryMetricUnit, it.notes) }
        tables.attempts.forEach { strings.add(it.side, it.source) }
        tables.attemptEvents.forEach { strings.add(it.type) }
        tables.attemptMetrics.forEach { strings.add(it.key, it.unit) }
        tables.attemptSpatialMarks.forEach { strings.add(it.type) }
        tables.encoderSessions.forEach { strings.add(it.sessionKey, it.exercise, it.source, it.timingMode, it.notes) }
        tables.encoderSamples.forEach { strings.add(it.trackingStatus, it.observationKind) }
        tables.encoderRepetitions.forEach { strings.add(it.quality, it.reasons) }
        tables.encoderRepMetrics.forEach { strings.add(it.key, it.unit, it.validity, it.reason) }
    }

    private class StringBudget(private val limits: ManualBackupPreflightLimits) {
        private var total = 0L
        fun add(vararg values: String?) {
            values.forEach { value ->
                if (value != null) {
                    require(value.length <= limits.maxStringChars) { "Manual backup is too large (string limit)" }
                    total += value.length
                    require(total <= limits.maxTotalStringChars) { "Manual backup is too large (text budget)" }
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
                        "Manual backup contains invalid Unicode"
                    }
                    bytes += 4
                    index++
                }
                Character.isLowSurrogate(char) -> throw IllegalArgumentException("Manual backup contains invalid Unicode")
                else -> bytes += 3
            }
            require(bytes <= maxBytes) { "Manual backup is too large" }
            index++
        }
        return bytes
    }

    private class Scanner(private val text: String, private val limits: ManualBackupPreflightLimits) {
        private var position = 0
        private var values = 0
        private var arrayItems = 0
        private var totalStringChars = 0L

        fun parse() {
            whitespace()
            value(0)
            whitespace()
            require(position == text.length) { "Invalid manual backup JSON" }
        }

        private fun value(depth: Int) {
            values++
            require(values <= limits.maxJsonValues) { "Manual backup is too large (too much structure)" }
            whitespace()
            require(position < text.length) { "Invalid manual backup JSON" }
            when (text[position]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> throw IllegalArgumentException("Invalid manual backup JSON")
            }
        }

        private fun objectValue(depth: Int) {
            require(depth <= limits.maxDepth) { "Manual backup is too large (nesting is too deep)" }
            position++
            whitespace()
            if (consume('}')) return
            while (true) {
                whitespace()
                require(position < text.length && text[position] == '"') { "Invalid manual backup JSON" }
                string(countTowardAggregate = false)
                whitespace()
                require(consume(':')) { "Invalid manual backup JSON" }
                value(depth)
                whitespace()
                if (consume('}')) return
                require(consume(',')) { "Invalid manual backup JSON" }
            }
        }

        private fun arrayValue(depth: Int) {
            require(depth <= limits.maxDepth) { "Manual backup is too large (nesting is too deep)" }
            position++
            whitespace()
            if (consume(']')) return
            while (true) {
                arrayItems++
                require(arrayItems <= limits.maxArrayItems) { "Manual backup is too large (too many rows)" }
                value(depth)
                whitespace()
                if (consume(']')) return
                require(consume(',')) { "Invalid manual backup JSON" }
            }
        }

        private fun string(countTowardAggregate: Boolean = true) {
            require(consume('"'))
            var decodedChars = 0
            while (position < text.length) {
                val char = text[position++]
                when {
                    char == '"' -> {
                        if (countTowardAggregate) totalStringChars += decodedChars
                        require(decodedChars <= limits.maxStringChars && (!countTowardAggregate || totalStringChars <= limits.maxTotalStringChars)) {
                            "Manual backup is too large (text budget)"
                        }
                        return
                    }
                    char == '\\' -> {
                        require(position < text.length) { "Invalid manual backup JSON string" }
                        when (text[position++]) {
                            '"', '\\', '/' -> decodedChars++
                            'b', 'f', 'n', 'r', 't' -> decodedChars++
                            'u' -> {
                                require(position + 4 <= text.length) { "Invalid manual backup JSON string" }
                                repeat(4) {
                                    require(text[position++].digitToIntOrNull(16) != null) { "Invalid manual backup JSON string" }
                                }
                                decodedChars++
                            }
                            else -> throw IllegalArgumentException("Invalid manual backup JSON string")
                        }
                    }
                    char.code < 0x20 -> throw IllegalArgumentException("Invalid manual backup JSON string")
                    else -> decodedChars++
                }
                // Bound escaped and unescaped strings while scanning, not after scanning a huge token.
                require(decodedChars <= limits.maxStringChars) { "Manual backup is too large (string limit)" }
                require(position <= text.length)
            }
            throw IllegalArgumentException("Invalid manual backup JSON string")
        }

        private fun number() {
            val start = position
            consume('-')
            require(position < text.length) { "Invalid manual backup number" }
            if (consume('0')) {
                require(position >= text.length || text[position] !in '0'..'9') { "Invalid manual backup number" }
            } else {
                require(text[position] in '1'..'9') { "Invalid manual backup number" }
                digits(start)
            }
            if (consume('.')) {
                require(position < text.length && text[position] in '0'..'9') { "Invalid manual backup number" }
                digits(start)
            }
            if (position < text.length && text[position] in "eE") {
                position++
                if (position < text.length && text[position] in "+-") position++
                require(position < text.length && text[position] in '0'..'9') { "Invalid manual backup number" }
                digits(start)
            }
            require(position - start <= limits.maxNumberChars) { "Manual backup is too large (number limit)" }
        }

        private fun digits(start: Int) {
            while (position < text.length && text[position] in '0'..'9') {
                position++
                require(position - start <= limits.maxNumberChars) { "Manual backup is too large (number limit)" }
            }
        }

        private fun literal(expected: String) {
            require(text.regionMatches(position, expected, 0, expected.length)) { "Invalid manual backup JSON" }
            position += expected.length
        }

        private fun whitespace() {
            while (position < text.length && (text[position] == ' ' || text[position] == '\t' || text[position] == '\r' || text[position] == '\n')) position++
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
