package com.rokidmirror.sender.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.rokidmirror.protocol.crypto.PairingCommitment

/** "Glasses: Pairing code 482 731 / Phone: Enter code" (spec pairing UX). The code is never logged. */
@Composable
fun PairingCodeDialog(receiverName: String, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val valid = PairingCommitment.normalizeInput(code) != null
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair with $receiverName") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { if (it.length <= 7) code = it },
                label = { Text("Code shown on the glasses") },
                placeholder = { Text("482 731") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSubmit(code) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
