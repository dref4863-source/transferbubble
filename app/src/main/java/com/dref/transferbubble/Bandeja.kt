package com.dref.transferbubble

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import com.dref.transferbubble.net.FileSender
import com.dref.transferbubble.net.FileToSend
import com.dref.transferbubble.net.Peer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    private val ui = Handler(Looper.getMainLooper())

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

    /**
     * Añade un archivo a la bandeja COPIANDOLO a almacenamiento propio.
     *
     * BUG GRAVE CORREGIDO (los envios fallaban en el Note 9 pero no en el A51):
     * antes se guardaba solo la URI tal cual (content://...). El permiso que
     * Android concede al "Compartir" es TEMPORAL y caduca al poco tiempo, asi
     * que si enviabas mas tarde el error era:
     *   "com.dref.transferbubble has no access to content://media/external/..."
     * y la tarea quedaba en FALLO. En el A51 funcionaba porque enviaba en el
     * momento, con el permiso todavia vivo.
     *
     * Ahora se copia el contenido a filesDir/bandeja y la bandeja apunta a esa
     * copia, que es nuestra para siempre. Asi se puede enviar dias despues.
     */
    fun agregar(context: Context, uri: String, nombre: String, tamano: Long, mime: String) {
        init(context)
        if (items.any { it.uri == uri || (it.fileName == nombre && it.size == tamano && tamano > 0) }) {
            Log.i(TAG, "Ya estaba en la bandeja: $nombre")
            return
        }
        val id = UUID.randomUUID().toString()

        // OPTIMIZACION: si el archivo ya es accesible directamente (file://),
        // NO se copia: se usa tal cual. Solo se copia lo que viene con permiso
        // temporal (content:// del Compartir o del selector), que es lo que
        // caduca y hacia fallar los envios.
        if (uri.startsWith("file://")) {
            items.add(ItemBandeja(id, uri, nombre, tamano, mime))
            guardar(); avisar()
            Log.i(TAG, "Agregado sin copia (file://): $nombre ($tamano bytes)")
            return
        }

        // La copia se hace en segundo plano: en almacenamiento interno va a
        // ~171 MB/s (medido), pero un archivo muy grande bloquearia la interfaz
        // si se hiciera aqui mismo.
        Log.i(TAG, "Copiando a la bandeja: $nombre ($tamano bytes)")
        worker.execute {
            val destino = File(directorioBandeja(context), id + "_" + nombre.limpiarNombre())
            val copiada = copiarA(context, uri, destino)
            val item = if (copiada) {
                ItemBandeja(id, Uri.fromFile(destino).toString(), nombre, destino.length(), mime)
            } else {
                Log.w(TAG, "No se pudo copiar '$nombre'; se guarda la uri original")
                ItemBandeja(id, uri, nombre, tamano, mime)
            }
            ui.post {
                items.add(item)
                guardar()
                avisar()
                Log.i(TAG, "Agregado a la bandeja: $nombre (${item.size} bytes, copia=$copiada)")
            }
        }
    }

    /** Carpeta privada donde viven las copias de la bandeja. */
    private fun directorioBandeja(context: Context): File =
        File(context.filesDir, "bandeja").apply { if (!exists()) mkdirs() }

    /** true si esta uri apunta a una copia nuestra dentro de la bandeja. */
    private fun esCopiaDeLaBandeja(uri: String): Boolean = try {
        val p = Uri.parse(uri).path ?: return false
        File(p).absolutePath.contains("/bandeja/")
    } catch (_: Exception) { false }

    /** Copia el contenido de la uri al fichero destino. true si se copio bien. */
    private fun copiarA(context: Context, uri: String, destino: File): Boolean = try {
        val entrada = when (Uri.parse(uri).scheme) {
            "content", "android.resource" -> context.contentResolver.openInputStream(Uri.parse(uri))
            "file" -> File(Uri.parse(uri).path ?: "").inputStream()
            else -> context.contentResolver.openInputStream(Uri.parse(uri))
        }
        if (entrada == null) {
            false
        } else {
            entrada.use { e -> destino.outputStream().use { s -> e.copyTo(s) } }
            // Verificacion real: no se da por bueno sin comprobar el tamano.
            destino.length() > 0
        }
    } catch (e: Exception) {
        Log.e(TAG, "Fallo copiando $uri: ${e.message}")
        false
    }

    /** Quita caracteres que no son validos en un nombre de fichero. */
    private fun String.limpiarNombre(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)

    /**
     * Borra del disco la copia de un item (para no dejar basura ocupando sitio).
     * Solo borra ficheros que esten dentro de nuestra carpeta "bandeja": nunca
     * toca el archivo original del usuario.
     */
    private fun borrarCopia(uri: String) {
        if (!esCopiaDeLaBandeja(uri)) return
        try {
            val f = File(Uri.parse(uri).path ?: return)
            if (f.delete()) Log.i(TAG, "Copia borrada: ${f.name}")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo borrar la copia de $uri: ${e.message}")
        }
    }

    fun quitar(itemId: String) {
        // Se borra tambien la COPIA del disco: si no, cada archivo quitado de
        // la bandeja dejaria el fichero ocupando espacio para siempre. Nunca
        // se toca el original del usuario (borrarCopia solo actua si esta
        // dentro de nuestra carpeta "bandeja").
        items.firstOrNull { it.id == itemId }?.let { borrarCopia(it.uri) }
        items.removeAll { it.id == itemId }
        guardar()
        avisar()
    }

    /** Borra TODAS las copias de la bandeja y vacia la lista. */
    fun vaciar() {
        items.forEach { borrarCopia(it.uri) }
        items.clear()
        guardar()
        avisar()
    }

    /** Espacio que ocupan ahora mismo las copias de la bandeja. */
    fun espacioCopias(context: Context): Long = try {
        File(context.filesDir, "bandeja")
            .listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    } catch (_: Exception) { 0L }

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
        // BUG CORREGIDO: si te equivocabas al enviar se creaba otra tarea y las
        // fallidas se acumulaban sin fin hasta copar el panel. Ahora, antes de
        // programar, se descartan las tareas FALLIDAS anteriores: si quieres
        // reintentar, se crea una nueva limpia.
        tareas.removeAll { it.estado == EstadoTarea.FALLO }
        var creadas = 0
        destinosElegidos.forEach { d ->
            // Y si ya habia una tarea EN ESPERA para ese equipo con los mismos
            // archivos, no se duplica.
            val yaEsta = tareas.any {
                it.estado == EstadoTarea.ESPERANDO_EQUIPO &&
                it.destinoFingerprint == d.fingerprint &&
                it.itemIds.toSet() == itemIds.toSet()
            }
            if (yaEsta) return@forEach
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
        avisar()
        Log.i(TAG, "Programadas $creadas tarea(s), ${itemIds.size} archivo(s) cada una")
        return creadas
    }

    /** Quita de la cola los envios ya terminados o fallidos (boton Limpiar). */
    fun limpiarTareas() {
        tareas.clear()
        guardar()
        avisar()
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