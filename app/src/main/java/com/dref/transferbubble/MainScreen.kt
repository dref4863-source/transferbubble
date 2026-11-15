package com.dref.transferbubble

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
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

/**
 * PANTALLA PRINCIPAL.
 *
 * CORRECCIONES DE ESTA VERSION (peticiones del usuario):
 *
 *  1. "en el panel no se puede deslizar hacia arriba / la opcion de activar
 *     burbuja queda fuera del foco de la pantalla".
 *     ANTES: el contenido era una Column FIJA con un LazyColumn con peso 1f en
 *     medio. La Column no se desplazaba, asi que cuando la bandeja crecia todo
 *     lo de abajo (incluido el boton "Activar burbuja") se salia por debajo y
 *     era INALCANZABLE.
 *     AHORA: toda la pantalla es un unico contenedor desplazable
 *     (verticalScroll). Se puede bajar hasta el final y llegar a cualquier
 *     control, tenga la bandeja los archivos que tenga.
 *
 *  2. "eliminar la informacion duplicada de la parte superior y dejar solo los
 *     archivos elegibles".
 *     ANTES habia una tarjeta "Sin archivos que enviar / Listo para enviar
 *     (n): ..." que repetia exactamente lo que ya muestra la bandeja.
 *     AHORA esa tarjeta ya NO existe: la unica lista de archivos es la bandeja
 *     (con sus checks), que es tambien donde se eligen los equipos.
 *
 *  3. "los archivos solo se pueden enviar desde la burbuja, y no se pueden
 *     desde el panel principal".
 *     CAUSA: el bucle de red vivia dentro de BurbujaService. Sin burbuja, nadie
 *     llamaba a Bandeja.procesar() y las tareas se quedaban en espera.
 *     AHORA el motor es Rastreo (lo arranca el receptor) y se puede disparar
 *     desde aqui con Rastreo.ahora().
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    activity: MainActivity,
    discovery: PeerDiscovery
) {
    val scope = rememberCoroutineScope()

    // Archivos que llegaron por el menu Compartir (estado de la Activity).
    val filesToSend by activity.sharedFiles

    // Equipos: los publica el motor compartido cada 7 s. Antes se buscaban solo
    // al pulsar el boton, y ademas por un camino independiente al de la burbuja.
    var peers by remember { mutableStateOf<List<Peer>>(Rastreo.peers) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Listo") }
    var progress by remember { mutableStateOf<Int?>(null) }
    var received by remember { mutableStateOf<List<String>>(emptyList()) }

    // Refresco de la bandeja cuando cambia algo (desde la app o desde la burbuja).
    var refrescoBandeja by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        // El servicio en primer plano avisa cuando llega un archivo.
        ReceiverService.onFileReceived = { name ->
            activity.runOnUiThread {
                received = received + name
                status = "Recibido: $name"
            }
        }
        // Escuchamos al motor de red (hilo aparte -> a la interfaz).
        val oyentePeers: (List<Peer>) -> Unit = { lista ->
            activity.runOnUiThread { peers = lista }
        }
        Rastreo.observar(oyentePeers)
        // Escuchamos a la bandeja: asi la pantalla refleja al instante lo que se
        // añade o se envia (tambien lo que se hace desde la burbuja).
        val oyenteBandeja: () -> Unit = {
            activity.runOnUiThread { refrescoBandeja++ }
        }
        Bandeja.observar(oyenteBandeja)

        // Arranca el motor si no lo estaba (normalmente lo arranca el receptor).
        Rastreo.iniciar(activity)
        peers = Rastreo.peers

        onDispose {
            ReceiverService.onFileReceived = null
            Rastreo.dejarDeObservar(oyentePeers)
            Bandeja.dejarDeObservar(oyenteBandeja)
        }
    }

    /** Fuerza un ciclo de busqueda inmediato (no espera los 7 s del motor). */
    fun scan() {
        scanning = true
        status = "Buscando equipos..."
        Rastreo.ahora(activity)
        // El resultado llega por el oyente; aqui solo quitamos el indicador.
        scope.launch {
            kotlinx.coroutines.delay(4200)
            peers = Rastreo.peers
            scanning = false
            status = if (peers.isEmpty())
                "No se encontro ningun equipo. Comprueba que la otra app este abierta."
            else "${peers.size} equipo(s) encontrado(s)"
        }
    }

    /**
     * Envio DIRECTO de lo que acaba de llegar por Compartir, sin pasar por la
     * bandeja. Sirve para el caso rapido: compartir -> tocar el equipo.
     */
    fun sendTo(peer: Peer) {
        if (filesToSend.isEmpty()) {
            status = "No hay archivos recien compartidos. Usa la bandeja para elegir."
            return
        }
        scope.launch {
            status = "Enviando a ${peer.alias}..."
            progress = 0
            val ok = withContext(Dispatchers.IO) {
                FileSender(activity).send(
                    peer = peer,
                    files = filesToSend,
                    senderAlias = Rastreo.aliasPropio(activity),
                    senderFingerprint = Rastreo.fingerprintPropio(activity)
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
                        if (scanning) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        else Icon(Icons.Default.Refresh, contentDescription = "Buscar equipos")
                    }
                }
            )
        }
    ) { padding ->
        // UN SOLO contenedor desplazable para toda la pantalla (arreglo del
        // "no se puede deslizar hacia arriba").
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(status, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

            // ---- estado del receptor (una linea, sin duplicar informacion)
            Spacer(Modifier.height(6.dp))
            val serviceRefresh = remember { mutableStateOf(0) }
            key(serviceRefresh.value) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            ReceiverService.isRunning -> "● Receptor activo"
                            ReceiverService.isPaused -> "● Receptor en pausa"
                            else -> "● Receptor detenido"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            ReceiverService.isRunning -> MaterialTheme.colorScheme.primary
                            ReceiverService.isPaused -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (ReceiverService.isRunning) {
                        TextButton(onClick = {
                            ReceiverService.send(activity, ReceiverService.ACTION_PAUSE)
                            serviceRefresh.value++
                        }) { Text("Pausar") }
                    } else {
                        TextButton(onClick = {
                            ReceiverService.send(activity, ReceiverService.ACTION_RESUME)
                            serviceRefresh.value++
                        }) { Text("Activar") }
                    }
                    TextButton(onClick = {
                        ReceiverService.send(activity, ReceiverService.ACTION_STOP)
                        serviceRefresh.value++
                    }) { Text("Detener") }
                }
            }

            progress?.let { p ->
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                Text("$p %", style = MaterialTheme.typography.bodySmall)
            }

            // ---- BANDEJA: unica lista de archivos y equipos (con checks)
            //
            // Aqui estaba la tarjeta duplicada "Listo para enviar (n)"; se ha
            // eliminado a peticion del usuario. La bandeja ya muestra los
            // archivos elegibles y es donde se marca que enviar y a quien.
            Spacer(Modifier.height(8.dp))
            key(refrescoBandeja) {
                BandejaCard(
                    activity = activity,
                    peers = peers,
                    alCambiar = { refrescoBandeja++ }
                )
            }

            // ---- aviso pequeno si acaba de llegar algo por Compartir
            if (filesToSend.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Compartido ahora: ${filesToSend.size} archivo(s). " +
                    "Ya estan en la bandeja; marca el equipo y pulsa Enviar.",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // ---- equipos cercanos (envio directo, sin pasar por la bandeja)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Equipos cercanos", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { scan() }, enabled = !scanning) {
                    Text(if (scanning) "Buscando..." else "Buscar")
                }
            }
            if (peers.isEmpty()) {
                Text(
                    "Sin equipos por ahora. Enciende TransferBubble en el otro movil.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            peers.forEach { peer ->
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
                        FilledTonalButton(onClick = { sendTo(peer) }) {
                            Icon(Icons.Default.Send, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Enviar")
                        }
                    }
                }
            }

            // ---- burbuja flotante (ahora SIEMPRE alcanzable al desplazar)
            Spacer(Modifier.height(14.dp))
            val tieneOverlay = remember { mutableStateOf(BurbujaService.tienePermiso(activity)) }
            val burbujaActiva = remember { mutableStateOf(BurbujaService.activa) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Burbuja flotante", fontWeight = FontWeight.Bold)
                    Text(
                        "Queda encima de todo. Tiene una ✕ para cerrarla desde ella misma.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!tieneOverlay.value) {
                            FilledTonalButton(onClick = {
                                activity.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:" + activity.packageName)
                                    )
                                )
                            }) { Text("Dar permiso") }
                        } else if (!burbujaActiva.value) {
                            FilledTonalButton(onClick = {
                                BurbujaService.start(activity)
                                burbujaActiva.value = true
                            }) { Text("Activar burbuja") }
                        } else {
                            FilledTonalButton(onClick = {
                                BurbujaService.stop(activity)
                                burbujaActiva.value = false
                            }) { Text("Ocultar burbuja") }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                !tieneOverlay.value -> "Falta el permiso de superposicion"
                                burbujaActiva.value -> "● Burbuja activa"
                                else -> "Burbuja desactivada"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "Recuerda: el boton Detener de la notificacion apaga el receptor Y la burbuja.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // ---- recibidos
            if (received.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Recibidos (Descargas/TransferBubble):", fontWeight = FontWeight.Bold)
                received.takeLast(5).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}