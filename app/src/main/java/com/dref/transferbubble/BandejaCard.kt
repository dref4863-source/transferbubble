package com.dref.transferbubble

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dref.transferbubble.net.Peer

/**
 * BANDEJA DENTRO DE LA APP (Fase 4).
 *
 * Es la misma idea que el panel de la burbuja, pero hecha con Compose porque
 * aqui si es una pantalla normal. El usuario pidio expresamente que la bandeja
 * de la aplicacion fuese igual a la de la burbuja: elegir con CHECKS que
 * archivos enviar y a que equipos, y que lo desconectado quede en espera.
 */
@Composable
fun BandejaCard(
    activity: MainActivity,
    peers: List<Peer>,
    alCambiar: () -> Unit
) {
    // Marcados: archivos y equipos. Se recuerdan mientras dure la pantalla.
    val marcadosArchivos = remember { mutableStateListOf<String>() }
    val marcadosEquipos = remember { mutableStateListOf<String>() }
    var aviso by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {

            // ---------- cabecera
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bandeja", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    // Abre el selector del sistema para añadir archivos.
                    activity.startActivity(
                        Intent(activity, ElegirArchivosActivity::class.java)
                    )
                }) { Text("Añadir") }
                if (Bandeja.tareas.isNotEmpty()) {
                    TextButton(onClick = {
                        Bandeja.limpiarTareas()
                        alCambiar()
                    }) { Text("Limpiar cola") }
                }
            }

            // ---------- 1) archivos
            Text("1) Elige los archivos", style = MaterialTheme.typography.bodySmall)
            if (Bandeja.items.isEmpty()) {
                Text(
                    "La bandeja esta vacia. Pulsa Añadir, o usa Compartir -> TransferBubble " +
                    "desde cualquier app.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Column {
                    Bandeja.items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = marcadosArchivos.contains(item.id),
                                onCheckedChange = { marcado ->
                                    if (marcado) marcadosArchivos.add(item.id)
                                    else marcadosArchivos.remove(item.id)
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.fileName, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${item.size / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(onClick = {
                                marcadosArchivos.remove(item.id)
                                Bandeja.quitar(item.id)
                                alCambiar()
                            }) { Text("Quitar") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------- 2) equipos
            Text("2) Elige los equipos", style = MaterialTheme.typography.bodySmall)
            val vistos = peers.associateBy { it.fingerprint }
            // Sin scroll propio: la pantalla entera ya se desplaza. (Anidar dos
            // scroll verticales en Compose lanza excepcion en tiempo de ejecucion.)
            Column {
                peers.forEach { peer ->
                    val conectado = true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = marcadosEquipos.contains(peer.fingerprint),
                            onCheckedChange = { marcado ->
                                if (marcado) {
                                    marcadosEquipos.add(peer.fingerprint)
                                    Bandeja.recordarDestino(
                                        Destino(peer.fingerprint, peer.alias, peer.deviceModel)
                                    )
                                } else marcadosEquipos.remove(peer.fingerprint)
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(peer.displayName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${peer.ip} · conectado",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    @Suppress("UNUSED_EXPRESSION") conectado
                }
                // Los desconectados siguen en la lista: se dejan programados.
                Bandeja.destinos.filter { !vistos.containsKey(it.fingerprint) }.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = marcadosEquipos.contains(d.fingerprint),
                            onCheckedChange = { marcado ->
                                if (marcado) marcadosEquipos.add(d.fingerprint)
                                else marcadosEquipos.remove(d.fingerprint)
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(d.displayName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "desconectado · quedara en espera",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                if (peers.isEmpty() && Bandeja.destinos.isEmpty()) {
                    Text(
                        "Sin equipos todavia. Pulsa Buscar equipos.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------- 3) enviar
            Button(
                onClick = {
                    val archivos = marcadosArchivos.toList()
                    val equipos = marcadosEquipos.mapNotNull { fp ->
                        val p = vistos[fp]
                        if (p != null) Destino(p.fingerprint, p.alias, p.deviceModel)
                        else Bandeja.destinos.firstOrNull { it.fingerprint == fp }
                    }
                    if (archivos.isEmpty() || equipos.isEmpty()) {
                        aviso = "Marca al menos un archivo y un equipo"
                    } else {
                        equipos.forEach { Bandeja.recordarDestino(it) }
                        val n = Bandeja.programarEnvios(archivos, equipos)
                        // IMPORTANTE (arreglo de "no se puede enviar desde el
                        // panel principal"): se pide al motor de red un ciclo
                        // INMEDIATO. Antes el panel de la app solo creaba las
                        // tareas y nadie las procesaba si la burbuja estaba
                        // apagada, asi que parecia que el boton no hacia nada.
                        Rastreo.ahora(activity)
                        aviso = "Programado: $n envio(s). Lo desconectado queda en espera."
                        alCambiar()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enviar lo marcado") }

            if (aviso.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(aviso, style = MaterialTheme.typography.bodySmall)
            }

            // ---------- cola
            if (Bandeja.tareas.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cola (${Bandeja.tareas.size})",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                // Cola: sin scroll propio tampoco (mismo motivo: anidar scrolls verticales
                // en Compose revienta). Se limita a los ultimos 6 y a la pantalla
                // entera le sobra scroll.
                Column {
                    Bandeja.tareas.takeLast(6).reversed().forEach { t ->
                        val color = when (t.estado) {
                            EstadoTarea.ENVIADO -> MaterialTheme.colorScheme.primary
                            EstadoTarea.FALLO -> MaterialTheme.colorScheme.error
                            EstadoTarea.ENVIANDO -> MaterialTheme.colorScheme.primary
                            EstadoTarea.ESPERANDO_EQUIPO -> MaterialTheme.colorScheme.tertiary
                        }
                        Text(
                            when (t.estado) {
                                EstadoTarea.ESPERANDO_EQUIPO -> "⏳ ${t.destinoNombre} · en espera"
                                EstadoTarea.ENVIANDO -> "⬆ ${t.destinoNombre} · ${t.progreso} %"
                                EstadoTarea.ENVIADO -> "✔ ${t.destinoNombre} · enviado"
                                EstadoTarea.FALLO -> "✖ ${t.destinoNombre} · fallo"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = color
                        )
                    }
                }
            }
        }
    }
}