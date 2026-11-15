package com.dref.transferbubble

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import com.dref.transferbubble.net.FileSender
import com.dref.transferbubble.net.FileToSend
import com.dref.transferbubble.net.Peer
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * FASE 4 - BANDEJA DE SALIDA
 *
 * Es el almacen intermedio que pidio el usuario: en lugar de arrastrar un
 * archivo dentro de la app, se van GUARDANDO cosas en una bandeja y luego se
 * elige (con checks):
 *   - QUE archivos enviar
 *   - A QUE equipos enviarlos
 * y si un equipo no esta conectado, la tarea queda EN ESPERA y se envia SOLA
 * en cuanto ese equipo aparezca en la red.
 *
 * Por que una bandeja y no arrastrar: Android aisla las apps, asi que la
 * burbuja flotante NO puede leer el archivo que hay debajo ni aceptar un
 * drag&drop de otra app. La bandeja es la forma de conseguir el mismo
 * resultado (guardarlo ahora, enviarlo cuando quieras) que SI es posible.
 *
 * Todo se guarda en SharedPreferences para que sobreviva al cierre de la app
 * y a un reinicio del telefono.
 */
data class ItemBandeja(
    val id: String,
    val uri: String,
    val fileName: String,
    val size: Long,
    val mimeType: String
)

/** Un equipo destino, aunque ahora mismo este desconectado. */
data class Destino(
    val fingerprint: String,
    val alias: String,
    val deviceModel: String? = null
) {
    val displayName: String
        get() = if (deviceModel.isNullOrBlank()) alias else "$alias ($deviceModel)"
}

enum class EstadoTarea { ESPERANDO_EQUIPO, ENVIANDO, ENVIADO, FALLO }

/** Envio programado: unos archivos concretos hacia un equipo concreto. */
data class TareaEnvio(
    val id: String,
    val destinoFingerprint: String,
    val destinoNombre: String,
    val itemIds: List<String>,
    val estado: EstadoTarea = EstadoTarea.ESPERANDO_EQUIPO,
    val detalle: String = "Esperando a que el equipo se conecte",
    val progreso: Int = 0
)

object Bandeja {

    private const val TAG = "TB-Bandeja"
    private const val PREFS = "tb_bandeja"

    /** Archivos guardados en la bandeja. */
    val items = mutableStateListOf<ItemBandeja>()

    /** Equipos elegidos como destino (se recuerdan aunque esten apagados). */
    val destinos = mutableStateListOf<Destino>()

    /** Cola de envios: pendientes, en curso y hechos. */
    val tareas = mutableStateListOf<TareaEnvio>()

    private var appContext: Context? = null
    private val worker = Executors.newSingleThreadExecutor()

    /** Aviso a la interfaz (burbuja/panel) cuando cambia algo. */
    @Volatile
    var onCambio: (() -> Unit)? = null

    private fun avisar() {
        // OJO: no llamar nunca desde el hilo principal en bucle; basta con
        // notificar y que cada interfaz se refresque en su propio hilo.
        try { onCambio?.invoke() } catch (_: Exception) {}
    }

    @Volatile
    private var cargado = false

    // ------------------------------------------------------------------ ciclo de vida

    fun init(context: Context) {
        if (cargado) return
        appContext = context.applicationContext
        cargar()
        cargado = true
        Log.i(TAG, "Bandeja cargada: ${items.size} archivo(s), ${tareas.size} tarea(s)")
    }

    // ------------------------------------------------------------------ archivos

    fun agregar(context: Context, uri: String, nombre: String, tamano: Long, mime: String) {
        init(context)
        if (items.any { it.uri == uri }) {
            Log.i(TAG, "Ya estaba en la bandeja: $nombre")
            return
        }
        items.add(ItemBandeja(UUID.randomUUID().toString(), uri, nombre, tamano, mime))
        guardar()
        avisar()
        Log.i(TAG, "Agregado a la bandeja: $nombre ($tamano bytes)")
    }

    fun quitar(itemId: String) {
        items.removeAll { it.id == itemId }
        guardar()
        avisar()
    }

    // ------------------------------------------------------------------ destinos

    /** Marca o desmarca un equipo como destino de la bandeja. */
    fun alternarDestino(peer: Peer) {
        val existente = destinos.firstOrNull { it.fingerprint == peer.fingerprint }
        if (existente != null) {
            destinos.remove(existente)
        } else {
            destinos.add(Destino(peer.fingerprint, peer.alias, peer.deviceModel))
        }
        guardar()
    }

    fun esDestino(fingerprint: String) = destinos.any { it.fingerprint == fingerprint }

    /** Guarda un equipo como destino conocido (para poder programarle envios). */
    fun recordarDestino(d: Destino) {
        if (destinos.none { it.fingerprint == d.fingerprint }) {
            destinos.add(d)
            guardar()
        }
    }

    /** Olvida un destino (por si el usuario ya no quiere verlo en la lista). */
    fun olvidarDestino(fingerprint: String) {
        destinos.removeAll { it.fingerprint == fingerprint }
        guardar()
    }

    // ------------------------------------------------------------------ envio

    /**
     * Crea las tareas de envio con los archivos marcados hacia los equipos marcados.
     * No envia todavia: las deja en cola y el servicio las dispara cuando puede.
     */
    fun programarEnvios(itemIds: List<String>, destinosElegidos: List<Destino>): Int {
        if (itemIds.isEmpty() || destinosElegidos.isEmpty()) return 0
        var creadas = 0
        destinosElegidos.forEach { d ->
            tareas.add(
                TareaEnvio(
                    id = UUID.randomUUID().toString(),
                    destinoFingerprint = d.fingerprint,
                    destinoNombre = d.displayName,
                    itemIds = itemIds
                )
            )
            creadas++
        }
        guardar()
        Log.i(TAG, "Programadas $creadas tarea(s), ${itemIds.size} archivo(s) cada una")
        return creadas
    }

    /**
     * Se llama cada vez que el descubrimiento termina: si alguna tarea esperaba
     * a un equipo que acaba de aparecer, se envia AUTOMATICAMENTE.
     */
    fun procesar(
        context: Context,
        peers: List<Peer>,
        aliasPropio: String,
        fingerprintPropio: String,
        onEstado: () -> Unit = {}
    ) {
        init(context)
        val porFingerprint = peers.associateBy { it.fingerprint }

        tareas.forEachIndexed { indice, tarea ->
            if (tarea.estado != EstadoTarea.ESPERANDO_EQUIPO) return@forEachIndexed
            val peer = porFingerprint[tarea.destinoFingerprint] ?: return@forEachIndexed

            actualizar(indice, tarea.copy(estado = EstadoTarea.ENVIANDO, detalle = "Enviando...", progreso = 0))
            avisar()

            worker.execute {
                val archivos = tarea.itemIds.mapNotNull { id ->
                    items.firstOrNull { it.id == id }?.let {
                        FileToSend(it.uri, it.fileName, it.size, it.mimeType)
                    }
                }
                if (archivos.isEmpty()) {
                    actualizar(indice, tarea.copy(estado = EstadoTarea.FALLO, detalle = "Archivos ya no disponibles"))
                    avisar()
                    return@execute
                }

                val ok = FileSender(context).send(
                    peer = peer,
                    files = archivos,
                    senderAlias = aliasPropio,
                    senderFingerprint = fingerprintPropio
                ) { p ->
                    // BUG CORREGIDO: antes se hacia tarea.copy(progreso = ...),
                    // partiendo de la tarea ORIGINAL, cuyo estado todavia era
                    // ESPERANDO_EQUIPO: eso revertia el estado de ENVIANDO a
                    // ESPERANDO_EQUIPO en cada aviso de progreso. Hay que
                    // partir del estado actual que ya hay en la lista.
                    val actual = tareas.getOrNull(indice) ?: tarea
                    actualizar(indice, actual.copy(progreso = p.percent))
                    avisar()
                }

                // Igual aqui: se parte del estado actual, no del original.
                val actual = tareas.getOrNull(indice) ?: tarea
                val nueva = if (ok) {
                    actual.copy(estado = EstadoTarea.ENVIADO, detalle = "Enviado a ${tarea.destinoNombre}", progreso = 100)
                } else {
                    actual.copy(estado = EstadoTarea.FALLO, detalle = "Fallo el envio a ${tarea.destinoNombre}")
                }
                actualizar(indice, nueva)
                Log.i(TAG, "Tarea ${tarea.destinoNombre}: ${nueva.estado}")
                avisar()
            }
        }
    }

    private fun actualizar(indice: Int, tarea: TareaEnvio) {
        if (indice in tareas.indices) tareas[indice] = tarea
    }

    /** Quita de la lista las tareas ya terminadas (limpieza manual de la UI). */
    fun limpiarTareasTerminadas() {
        tareas.removeAll { it.estado == EstadoTarea.ENVIADO || it.estado == EstadoTarea.FALLO }
        guardar()
        avisar()
    }

    // ------------------------------------------------------------------ persistencia

    private fun guardar() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val itemsJson = JSONArray()
        items.forEach { i ->
            itemsJson.put(JSONObject().apply {
                put("id", i.id); put("uri", i.uri); put("fileName", i.fileName)
                put("size", i.size); put("mimeType", i.mimeType)
            })
        }
        val destinosJson = JSONArray()
        destinos.forEach { d ->
            destinosJson.put(JSONObject().apply {
                put("fingerprint", d.fingerprint); put("alias", d.alias)
                put("deviceModel", d.deviceModel ?: "")
            })
        }
        val tareasJson = JSONArray()
        tareas.forEach { t ->
            // Las tareas ya ENVIADAS no se persisten: no hay que reenviarlas al reabrir.
            if (t.estado == EstadoTarea.ENVIADO) return@forEach
            tareasJson.put(JSONObject().apply {
                put("id", t.id); put("destinoFingerprint", t.destinoFingerprint)
                put("destinoNombre", t.destinoNombre)
                put("itemIds", JSONArray(t.itemIds))
                put("estado", t.estado.name); put("detalle", t.detalle)
                put("progreso", t.progreso)
            })
        }
        prefs.edit()
            .putString("items", itemsJson.toString())
            .putString("destinos", destinosJson.toString())
            .putString("tareas", tareasJson.toString())
            .apply()
    }

    private fun cargar() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            JSONArray(prefs.getString("items", "[]")).let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(
                        ItemBandeja(
                            o.optString("id"), o.optString("uri"), o.optString("fileName"),
                            o.optLong("size"), o.optString("mimeType", "application/octet-stream")
                        )
                    )
                }
            }
            JSONArray(prefs.getString("destinos", "[]")).let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    destinos.add(
                        Destino(
                            o.optString("fingerprint"), o.optString("alias"),
                            o.optString("deviceModel").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            JSONArray(prefs.getString("tareas", "[]")).let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ids = o.optJSONArray("itemIds") ?: JSONArray()
                    val lista = (0 until ids.length()).map { ids.getString(it) }
                    tareas.add(
                        TareaEnvio(
                            id = o.optString("id"),
                            destinoFingerprint = o.optString("destinoFingerprint"),
                            destinoNombre = o.optString("destinoNombre"),
                            itemIds = lista,
                            // Lo que estaba enviandose al cerrar vuelve a la cola.
                            estado = EstadoTarea.ESPERANDO_EQUIPO,
                            detalle = "Esperando a que el equipo se conecte"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo cargar la bandeja: ${e.message}")
        }
    }
}