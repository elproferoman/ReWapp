@file:OptIn(ExperimentalMaterial3Api::class)

package app.rewapp

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

private fun formatDelay(minutes: Int): String = when {
    minutes % (60 * 24) == 0 && minutes >= 60 * 24 -> "${minutes / (60 * 24)} d"
    minutes % 60 == 0 && minutes >= 60 -> "${minutes / 60} h"
    else -> "$minutes min"
}

// ===================================================================== REGLAS

@Composable
fun RulesTab(modifier: Modifier, rev: Int, tick: Int, bump: () -> Unit, onEdit: (Rule?) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember(rev) { Store.settings() }
    val rules = remember(rev) { Store.rules() }
    val listenerOk = remember(tick, rev) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Respondedor automático", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (settings.enabled) "Activo: responde WhatsApp Business" else "Apagado: no responde nada",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = settings.enabled,
                                onCheckedChange = {
                                    Store.saveSettings(settings.copy(enabled = it))
                                    bump()
                                },
                            )
                        }
                        if (!listenerOk) {
                            Text(
                                "⚠️ Falta el permiso de acceso a notificaciones. Sin él la app no puede leer ni responder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(onClick = {
                                ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("Dar permiso") }
                        } else {
                            Text("✅ Permiso de notificaciones concedido", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Quitar ahorro de batería") }
                    }
                }
            }
            if (rules.isEmpty()) {
                item { Text("Todavía no hay reglas. Tocá «Nueva regla».") }
            }
            items(rules, key = { it.id }) { r ->
                val parent = rules.find { it.id == r.afterRuleId }
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(r) },
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(r.name.ifBlank { "(sin nombre)" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (r.trigger.isBlank()) "Cualquier mensaje"
                                else "${r.matchType.label}: ${r.trigger.replace("\n", " | ")}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (parent != null) {
                                Text("↳ Solo después de: ${parent.name}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                r.reply.take(90).replace("\n", " ") + if (r.reply.length > 90) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (r.followUpEnabled) {
                                Text(
                                    "⏰ Insiste a los ${formatDelay(r.followUpDelayMinutes)} (hasta ${r.followUpMaxAttempts} ${if (r.followUpMaxAttempts == 1) "vez" else "veces"})",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Switch(
                            checked = r.enabled,
                            onCheckedChange = { on ->
                                Store.saveRules(Store.rules().map { if (it.id == r.id) it.copy(enabled = on) else it })
                                bump()
                            },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onEdit(null) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Nueva regla") },
        )
    }
}

// ===================================================================== EDITOR

@Composable
fun RuleEditor(
    initial: Rule?,
    allRules: List<Rule>,
    onSave: (Rule) -> Unit,
    onDelete: (Rule) -> Unit,
    onCancel: () -> Unit,
) {
    val base = remember { initial ?: Rule(trigger = "", reply = "") }
    var name by remember { mutableStateOf(base.name) }
    var matchType by remember { mutableStateOf(base.matchType) }
    var trigger by remember { mutableStateOf(base.trigger) }
    var reply by remember { mutableStateOf(base.reply) }
    var afterId by remember { mutableStateOf(base.afterRuleId) }
    var once by remember { mutableStateOf(base.oncePerContact) }
    var onlyNew by remember { mutableStateOf(base.onlyNewConversation) }
    var onlyUnsaved by remember { mutableStateOf(base.onlyUnsaved) }
    var followUp by remember { mutableStateOf(base.followUpEnabled) }
    var followMsg by remember { mutableStateOf(base.followUpMessage) }
    var attemptsText by remember { mutableStateOf(base.followUpMaxAttempts.toString()) }

    // El plazo se muestra en la unidad que mejor lo representa.
    val m = base.followUpDelayMinutes
    var unit by remember { mutableStateOf(if (m % 1440 == 0) 1440 else if (m % 60 == 0) 60 else 1) }
    var delayText by remember { mutableStateOf((m / unit).toString()) }

    var afterMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val others = allRules.filter { it.id != base.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Nueva regla" else "Editar regla") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nombre de la regla") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Text("Cuando llegue un mensaje que…", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MatchType.entries.forEach { t ->
                    FilterChip(selected = matchType == t, onClick = { matchType = t }, label = { Text(t.label) })
                }
            }
            OutlinedTextField(
                value = trigger, onValueChange = { trigger = it },
                label = { Text("Texto") }, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Varias opciones separadas por | . Vacío = cualquier mensaje. No distingue mayúsculas ni tildes.") },
            )

            Text("Solo si el contacto…", style = MaterialTheme.typography.titleSmall)
            Box {
                OutlinedButton(onClick = { afterMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (afterId == null) "Escribe por primera vez / mensaje inicial"
                        else "Viene de: " + (allRules.find { it.id == afterId }?.name ?: "(regla borrada)"),
                    )
                }
                DropdownMenu(expanded = afterMenu, onDismissRequest = { afterMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Escribe por primera vez / mensaje inicial") },
                        onClick = { afterId = null; afterMenu = false },
                    )
                    others.forEach { o ->
                        DropdownMenuItem(
                            text = { Text("Viene de: ${o.name.ifBlank { "(sin nombre)" }}") },
                            onClick = { afterId = o.id; afterMenu = false },
                        )
                    }
                }
            }
            Text(
                "Para un segundo paso, elegí la regla anterior: esta solo se activa si ya le respondiste con esa.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = reply, onValueChange = { reply = it },
                label = { Text("Mensaje de respuesta") }, modifier = Modifier.fillMaxWidth(), minLines = 4,
                supportingText = { Text("Podés usar {nombre} para el nombre del contacto, emojis y varios renglones.") },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Responder una sola vez por contacto")
                    Text("Evita repetir la misma respuesta.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = once, onCheckedChange = { once = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solo si la app todavía no le respondió a este contacto")
                    Text(
                        "La app no ve el historial de WhatsApp: un chat viejo cuenta como nuevo. Para excluir clientes conocidos usá «solo números no guardados».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = onlyNew, onCheckedChange = { onlyNew = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solo para números que no tengo guardados")
                    Text(
                        "Si el contacto está en tu agenda (aparece con nombre), esta regla no responde.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = onlyUnsaved, onCheckedChange = { onlyUnsaved = it })
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Insistir si no responde", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Si después de esta respuesta la persona no escribe nada, se le manda otro mensaje.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = followUp, onCheckedChange = { followUp = it })
            }
            if (followUp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 4) delayText = v },
                        label = { Text("Esperar") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                    FilterChip(selected = unit == 1, onClick = { unit = 1 }, label = { Text("min") })
                    FilterChip(selected = unit == 60, onClick = { unit = 60 }, label = { Text("horas") })
                    FilterChip(selected = unit == 1440, onClick = { unit = 1440 }, label = { Text("días") })
                }
                OutlinedTextField(
                    value = followMsg, onValueChange = { followMsg = it },
                    label = { Text("Mensaje para insistir") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                )
                OutlinedTextField(
                    value = attemptsText,
                    onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 1) attemptsText = v },
                    label = { Text("Cantidad máxima de insistencias (1 a 5)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val delayMin = (delayText.toIntOrNull() ?: 0) * unit
                    when {
                        reply.isBlank() -> error = "Falta el mensaje de respuesta."
                        followUp && followMsg.isBlank() -> error = "Falta el mensaje para insistir."
                        followUp && delayMin < 1 -> error = "El tiempo de espera tiene que ser al menos 1 minuto."
                        else -> onSave(
                            base.copy(
                                name = name.ifBlank { reply.take(30) },
                                matchType = matchType,
                                trigger = trigger,
                                reply = reply,
                                afterRuleId = afterId,
                                oncePerContact = once,
                                onlyNewConversation = onlyNew,
                                onlyUnsaved = onlyUnsaved,
                                followUpEnabled = followUp,
                                followUpDelayMinutes = if (followUp) delayMin else base.followUpDelayMinutes,
                                followUpMessage = followMsg,
                                followUpMaxAttempts = (attemptsText.toIntOrNull() ?: 1).coerceIn(1, 5),
                            ),
                        )
                    }
                },
            ) { Text("Guardar") }

            if (initial != null) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmDelete = true },
                ) { Text("Eliminar regla", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar la regla?") },
            text = { Text("Las reglas que dependían de esta pasarán a ser reglas de inicio.") },
            confirmButton = { TextButton(onClick = { onDelete(initial) }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

// ===================================================================== ACTIVIDAD

@Composable
fun ActivityTab(modifier: Modifier, rev: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val logs = remember(rev) { Store.logs() }
    val pending = remember(rev) { Store.allStates().filter { it.followUpDueAt > 0 }.sortedBy { it.followUpDueAt } }
    val rules = remember(rev) { Store.rules() }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Insistencias pendientes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = bump) { Text("Actualizar") }
            }
        }
        if (pending.isEmpty()) item { Text("Ninguna.", style = MaterialTheme.typography.bodySmall) }
        items(pending, key = { "p_" + it.key }) { st ->
            val ruleName = rules.find { it.id == st.followUpRuleId }?.name ?: "-"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "• ${st.name.ifBlank { st.key }} — ${dateFmt.format(Date(st.followUpDueAt))} ($ruleName)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { FollowUps.cancel(ctx, st.key); bump() }) { Text("Cancelar") }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Registro", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val text = logs.joinToString("\n") { e ->
                        "${dateFmt.format(Date(e.time))} | ${e.contact} | ${e.incoming.take(80)} | ${e.rule} | ${e.outcome}"
                    }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    ctx.startActivity(Intent.createChooser(send, "Compartir registro").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Compartir") }
                TextButton(onClick = { Store.clearLog(); bump() }) { Text("Limpiar") }
            }
        }
        if (logs.isEmpty()) item { Text("Sin actividad todavía.", style = MaterialTheme.typography.bodySmall) }
        items(logs) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${dateFmt.format(Date(e.time))} · ${e.contact}", style = MaterialTheme.typography.labelMedium)
                    if (e.incoming.isNotBlank()) Text("Recibido: ${e.incoming.take(120)}", style = MaterialTheme.typography.bodySmall)
                    if (e.rule != "-") Text("Regla: ${e.rule}", style = MaterialTheme.typography.bodySmall)
                    Text(e.outcome, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ===================================================================== AJUSTES

@Composable
fun SettingsTab(modifier: Modifier, rev: Int, bump: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember(rev) { Store.settings() }
    var delayText by remember(rev) { mutableStateOf(settings.replyDelaySec.toString()) }
    var forgetText by remember(rev) { mutableStateOf(settings.forgetDays.toString()) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(Store.exportRules().toByteArray()) }
            }.onSuccess { Toast.makeText(ctx, "Reglas exportadas", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "No se pudo exportar", Toast.LENGTH_LONG).show() }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingImport = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (pendingImport == null) Toast.makeText(ctx, "No se pudo leer el archivo", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlinedTextField(
            value = delayText,
            onValueChange = { v ->
                if (v.all { it.isDigit() } && v.length <= 3) {
                    delayText = v
                    v.toIntOrNull()?.let { Store.saveSettings(Store.settings().copy(replyDelaySec = it)) }
                }
            },
            label = { Text("Demora antes de responder (segundos)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = forgetText,
            onValueChange = { v ->
                if (v.all { it.isDigit() } && v.length <= 3) {
                    forgetText = v
                    v.toIntOrNull()?.takeIf { it >= 1 }?.let { Store.saveSettings(Store.settings().copy(forgetDays = it)) }
                }
            },
            label = { Text("Tratar al contacto como nuevo tras (días sin actividad)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("También responder en WhatsApp común")
                Text("Por defecto solo WhatsApp Business.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.includeWhatsApp, onCheckedChange = { Store.saveSettings(settings.copy(includeWhatsApp = it)); bump() })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Registrar también lo que se ignora")
                Text("Sirve para ver por qué un mensaje no recibió respuesta.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.debug, onCheckedChange = { Store.saveSettings(settings.copy(debug = it)); bump() })
        }
        HorizontalDivider()
        OutlinedButton(onClick = { exportLauncher.launch("rewapp-reglas.json") }, modifier = Modifier.fillMaxWidth()) {
            Text("Exportar reglas a un archivo")
        }
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Importar reglas desde un archivo")
        }
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Reiniciar historial de contactos")
        }
        Text(
            "El historial de contactos guarda en qué paso está cada persona. Al reiniciarlo, todos vuelven a empezar de cero.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    pendingImport?.let { raw ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("¿Reemplazar las reglas?") },
            text = { Text("Se van a borrar las reglas actuales y se cargarán las del archivo.") },
            confirmButton = {
                TextButton(onClick = {
                    val n = Store.importRules(raw)
                    Toast.makeText(ctx, if (n < 0) "El archivo no es válido" else "Se importaron $n reglas", Toast.LENGTH_LONG).show()
                    pendingImport = null
                    bump()
                }) { Text("Reemplazar") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancelar") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("¿Reiniciar el historial?") },
            text = { Text("También se cancelan las insistencias pendientes.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.clearStates()
                    FollowUps.schedule(ctx)
                    confirmReset = false
                    bump()
                }) { Text("Reiniciar") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
        )
    }
}
