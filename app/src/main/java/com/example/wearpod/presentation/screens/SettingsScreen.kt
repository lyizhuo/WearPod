package com.example.wearpod.presentation.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    currentOpmlId: String?,
    onLoadOpml: (String) -> Unit
) {
    var inputId by remember { mutableStateOf("") }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
        item {
            ListHeader {
                Text(text = "Settings", textAlign = TextAlign.Center)
            }
        }
        item {
            Text(
                text = "Current ID: ${currentOpmlId ?: "Local Default"}", 
                style = MaterialTheme.typography.bodySmall, 
                color = MaterialTheme.colorScheme.onBackground.copy(alpha=0.6f)
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = inputId,
                    onValueChange = { inputId = it },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceContainer, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(8.dp),
                    decorationBox = { innerTextField ->
                        if (inputId.isEmpty()) {
                            Text("Enter ID...", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                        innerTextField()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { if(inputId.isNotEmpty()) { onLoadOpml(inputId); inputId = "" } },
                    modifier = Modifier.size(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Check, "Submit")
                }
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { /* TODO: About or Version info */ },
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text("About WearPod", maxLines = 1) },
                icon = { Icon(Icons.Default.Info, contentDescription = "Info") }
            )
        }
        // More settings can go here in the future
    }
}
