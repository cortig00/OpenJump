package com.openjump.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.openjump.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Shared date input for OpenJump. Dates are selected with the Material date picker
 * and displayed using the active locale instead of requiring a typed ISO value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenJumpDateField(
    date: LocalDate?,
    onDateChange: (LocalDate?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowClear: Boolean = true,
    maxDate: LocalDate? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()) }
    val displayedDate = date?.format(formatter).orEmpty()

    Box(modifier) {
        OutlinedTextField(
            value = displayedDate,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_calendar),
                    contentDescription = null,
                )
            },
        )
        Spacer(
            Modifier
                .matchParentSize()
                .semantics {
                    contentDescription = label
                    if (displayedDate.isNotEmpty()) stateDescription = displayedDate
                    role = Role.Button
                }
                .clickable(enabled = enabled, role = Role.Button) { showPicker = true },
        )
    }

    if (showPicker) {
        val selectableDates = remember(maxDate) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    maxDate == null || datePickerMillisToLocalDate(utcTimeMillis) <= maxDate

                override fun isSelectableYear(year: Int): Boolean = maxDate == null || year <= maxDate.year
            }
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date?.toDatePickerMillis(),
            selectableDates = selectableDates,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onDateChange(datePickerMillisToLocalDate(it)) }
                        showPicker = false
                    },
                    enabled = pickerState.selectedDateMillis != null,
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                Row {
                    if (allowClear && date != null) {
                        TextButton(onClick = { onDateChange(null); showPicker = false }) {
                            Text(stringResource(R.string.common_clear))
                        }
                    }
                    TextButton(onClick = { showPicker = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

internal fun LocalDate.toDatePickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun datePickerMillisToLocalDate(value: Long): LocalDate =
    Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate()
