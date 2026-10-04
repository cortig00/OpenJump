package com.openjump.app.ui

import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Formateo de timestamps y métricas para la UI.
 */
object Formatting {

    /** Timestamp µs -> "00:01.762500" (min:seg.microsegundos). */
    fun usToClock(us: Long): String {
        val totalMicros = us.coerceAtLeast(0)
        val minutes = totalMicros / 60_000_000L
        val seconds = (totalMicros % 60_000_000L) / 1_000_000L
        val micros = totalMicros % 1_000_000L
        return String.format(Locale.getDefault(), "%02d:%02d.%06d", minutes, seconds, micros)
    }

    /** Timestamp µs -> "00:01.762" (min:seg.milliseconds), readable in results. */
    fun usToClockMs(us: Long): String {
        val totalMs = us.coerceAtLeast(0) / 1_000L
        val minutes = totalMs / 60_000L
        val seconds = (totalMs % 60_000L) / 1_000L
        val millis = totalMs % 1_000L
        return String.format(Locale.getDefault(), "%02d:%02d.%03d", minutes, seconds, millis)
    }

    /** Segundos -> "1.204167 s". */
    fun secondsToText(seconds: Double): String =
        String.format(Locale.getDefault(), "%.6f s", seconds)

    /** Fecha/hora adaptada al locale indicado o, por compatibilidad, al locale del proceso. */
    fun dateTimeToText(epochMillis: Long, locale: Locale = Locale.getDefault()): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(epochMillis))
}
