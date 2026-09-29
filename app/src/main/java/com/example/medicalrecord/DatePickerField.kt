package com.example.medicalrecord

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(label: String, value: String, onDateSelected: (String) -> Unit, optional: Boolean = false) {
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
            Text("$label：${value.ifBlank { "选择日期" }}")
        }
        if (optional && value.isNotBlank()) TextButton(onClick = { onDateSelected("") }) { Text("清空") }
    }
    if (picking) {
        val initial = runCatching { LocalDate.parse(value) }.getOrNull() ?: LocalDate.now()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
                }
                picking = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("取消") } }
        ) { DatePicker(state = pickerState) }
    }
}
