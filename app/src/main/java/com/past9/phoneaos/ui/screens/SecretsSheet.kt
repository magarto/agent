package com.past9.phoneaos.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.past9.phoneaos.R
import com.past9.phoneaos.tools.AgentSecrets
import com.past9.phoneaos.ui.AppCard

/**
 * Settings > Automation > Secrets: named passwords and keys the agent uses as {{secret:name}} without seeing them.
 * The list shows names and notes only; a value is typed here once, masked, and never shown again.
 */
@Composable
internal fun SecretsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { com.past9.phoneaos.App.graph(ctx).settings }
    var secrets by remember { mutableStateOf(settings.agentSecrets()) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val clean = AgentSecrets.cleanName(name)
    com.past9.phoneaos.ui.AgentSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding()) {
            com.past9.phoneaos.ui.SheetHead(Icons.Rounded.Lock, stringResource(R.string.settings_automation), stringResource(R.string.secrets_title), stringResource(R.string.secrets_body))
            Spacer(Modifier.height(20.dp))
            AppCard(padding = PaddingValues(vertical = 4.dp)) {
                if (secrets.isEmpty()) Text(stringResource(R.string.secrets_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                secrets.toSortedMap().forEach { (n, about) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
                            if (about.isNotBlank()) Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val removeDesc = stringResource(R.string.secrets_remove, n)
                        IconButton(onClick = { settings.removeAgentSecret(n); secrets = settings.agentSecrets() }, modifier = Modifier.semantics { contentDescription = removeDesc }) {
                            Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val plain = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
            com.past9.phoneaos.ui.SheetField(name, { name = it }, stringResource(R.string.secrets_name), stringResource(R.string.secrets_name_hint), singleLine = true, mono = true, keyboard = plain)
            if (name.isNotBlank() && clean == null) Text(stringResource(R.string.secrets_bad_name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            Spacer(Modifier.height(12.dp))
            com.past9.phoneaos.ui.SheetField(value, { value = it }, stringResource(R.string.secrets_value), "", singleLine = true, secret = true,
                keyboard = plain.copy(keyboardType = KeyboardType.Password))
            Spacer(Modifier.height(12.dp))
            com.past9.phoneaos.ui.SheetField(note, { note = it }, stringResource(R.string.secrets_note), stringResource(R.string.secrets_note_hint), singleLine = true)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { settings.setAgentSecret(clean!!, value, note); secrets = settings.agentSecrets(); name = ""; value = ""; note = "" },
                enabled = clean != null && value.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.secrets_add), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
