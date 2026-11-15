package com.dref.transferbubble

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dref.transferbubble.net.FileSender
import com.dref.transferbubble.net.Peer
import com.dref.transferbubble.net.PeerDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: MainActivity,
    discovery: PeerDiscovery
) {
    val scope = rememberCoroutineScope()

    // Leemos el estado observable de la Activity: asi al compartir desde otra
    // app sin cerrar la nuestra, la interfaz se actualiza sola.
    val filesToSend by activity.sharedFiles

    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Listo") }
    var progress by remember { mutableStateOf<Int?>(null) }
    var received by remember { mutableStateOf<List<String>>(emptyList()) }

    // El servicio en primer plano avisa cuando llega un archivo, aunque esta
    // pantalla no sea la que gestiona el receptor.
    DisposableEffect(Unit) {
        ReceiverService.onFileReceived = { name ->
            activity.runOnUiThread {
                received = received + name
                status = "Recibido: $name"
            }
        }
        onDispose { ReceiverService.onFileReceived = null }
    }

    fun scan() {
        scope.launch {
            scanning = true
            status = "Buscando equipos..."
            val result = withContext(Dispatchers.IO) { discovery.discover() }
            peers = result
            scanning = false
            status = if (result.isEmpty())
                "No se encontro ningun equipo. Comprueba que la otra app este abierta."
            else "${result.size} equipo(s) encontrado(s)"
        }
    }

    fun sendTo(peer: Peer) {
        if (filesToSend.isEmpty()) {
            status = "Primero comparte un archivo desde otra app (Compartir -> TransferBubble)"
            return
        }
        scope.launch {
            status = "Enviando a ${peer.alias}..."
            progress = 0
            val ok = withContext(Dispatchers.IO) {
                FileSender(activity).send(
                    peer = peer,
                    files = filesToSend,
                    senderAlias = discovery.deviceAlias,
                    senderFingerprint = discovery.selfFingerprint
                ) { p -> progress = p.percent }
            }
            progress = null
            status = if (ok) "Enviado a ${peer.alias} correctamente" else "Fallo el envio a ${peer.alias}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TransferBubble") },
                actions = {
                    IconButton(onClick = { scan() }, enabled = !scanning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Buscar")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(status, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

            // Indica si el receptor esta activo en segundo plano.
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (ReceiverService.isRunning) "● Receptor activo en segundo plano"
                    else "● Receptor detenido",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ReceiverService.isRunning)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }

            progress?.let { p ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                Text("$p %", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))

            // Aviso claro del estado del archivo que se va a enviar
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    if (filesToSend.isEmpty()) {
                        Text("Sin archivos que enviar", fontWeight = FontWeight.Bold)
                        Text(
                            "Usa Compartir desde la Galeria o cualquier app y elige TransferBubble.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("Listo para enviar (${filesToSend.size}):", fontWeight = FontWeight.Bold)
                        filesToSend.take(5).forEach {
                            Text("• ${it.fileName} (${it.size / 1024} KB)",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Equipos cercanos", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(peers) { peer ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = { sendTo(peer) }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.displayName, fontWeight = FontWeight.SemiBold)
                                Text("${peer.ip} · v${peer.version}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            // Boton explicito: en la prueba anterior no quedaba
                            // claro que hubiera que pulsar para enviar.
                            FilledTonalButton(onClick = { sendTo(peer) }) {
                                Icon(Icons.Default.Send, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Enviar")
                            }
                        }
                    }
                }
            }

            if (received.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Recibidos (Descargas/TransferBubble):", fontWeight = FontWeight.Bold)
                received.takeLast(3).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { scan() }, modifier = Modifier.fillMaxWidth(), enabled = !scanning) {
                Text(if (scanning) "Buscando..." else "Buscar equipos")
            }
        }
    }

    LaunchedEffect(Unit) { scan() }
}