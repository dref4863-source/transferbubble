package com.dref.transferbubble.net

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Servidor HTTP que actua como receptor del protocolo LocalSend v2.2.
 *
 * Decisiones de diseno tomadas a raiz de las pruebas reales:
 *  - CONCURRENTE: cada peticion se atiende en su propio hilo. En la prueba el
 *    servidor secuencial se colgaba cuando el cliente abortaba una descarga
 *    (ConnectionAbortedError). Aqui una desconexion NUNCA bloquea al resto.
 *  - Escucha en 0.0.0.0 para responder tanto por WiFi como por hotspot.
 */
class HttpReceiver(private val context: Context) {

    companion object {
        private const val TAG = "TB-Receiver"
        const val PORT = 53317
        private const val CRLF = "\r\n"
    }

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    /** Ids de sesion ya aceptados: no volver a preguntar al usuario. */
    private val acceptedSessions = mutableSetOf<String>()

    /**
     * Carpeta destino.
     *
     * IMPORTANTE (bug corregido tras la prueba real): en Android 10+ con
     * targetSdk 34 el acceso directo a /storage/emulated/0/Download esta
     * bloqueado por scoped storage: mkdirs() devolvia false EN SILENCIO y al
     * escribir salia "ENOENT (No such file or directory)".
     *
     * Solucion: guardar primero en el almacenamiento PRIVADO de la app, que
     * siempre es escribible sin permisos. Despues se publica una copia en
     * Descargas a traves de MediaStore (ver publishToDownloads).
     */
    val receiveDir: File by lazy {
        val dir = File(context.filesDir, "received")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "No se pudo crear la carpeta privada: ${dir.absolutePath}")
        }
        Log.i(TAG, "Carpeta de recepcion: ${dir.absolutePath} (existe=${dir.exists()})")
        dir
    }

    /** Se llama cuando llega un prepare-upload, para avisar a la UI. */
    var onIncomingRequest: ((sessionId: String, alias: String, totalBytes: Long) -> Unit)? = null

    /** Se llama al terminar de recibir un archivo. */
    var onFileReceived: ((File) -> Unit)? = null

    fun start() {
        if (running) return
        try {
            serverSocket = ServerSocket(PORT).apply { reuseAddress = true }
            running = true
            pool.execute {
                Log.i(TAG, "Servidor escuchando en 0.0.0.0:$PORT")
                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        // Cada cliente en su hilo: nunca bloquea a los demas.
                        pool.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Error aceptando: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el puerto $PORT: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    // ------------------------------------------------------------------ cliente

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val header = readHeaders(input) ?: return

            val firstLine = header.lineSequence().first()
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            val contentLength = header.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0

            Log.i(TAG, "-> $method $path (${contentLength} bytes)")

            when {
                method == "POST" && path.startsWith("/api/localsend/v2/register") ->
                    handleRegister(client, contentLength)

                method == "POST" && path.startsWith("/api/localsend/v2/prepare-upload") ->
                    handlePrepareUpload(client, contentLength)

                method == "POST" && path.startsWith("/api/localsend/v2/upload") ->
                    handleUpload(client, path, contentLength)

                method == "POST" && path.startsWith("/api/localsend/v2/cancel") ->
                    respond(client, 200, "OK")

                method == "GET" && path.startsWith("/api/localsend/v2/info") ->
                    respond(client, 200, infoJson())

                else -> respond(client, 404, "no")
            }
        } catch (e: Exception) {
            // Desconexion abrupta del cliente: se registra y se sigue.
            Log.w(TAG, "Cliente desconectado: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** Lee cabeceras hasta la linea en blanco. */
    private fun readHeaders(input: InputStream): String? {
        val sb = StringBuilder()
        val buf = ByteArray(1)
        var last4 = ""
        while (input.read(buf) != -1) {
            val c = buf[0].toInt().toChar()
            sb.append(c)
            last4 = (last4 + c).takeLast(4)
            if (last4 == "$CRLF$CRLF") break
            if (sb.length > 16384) break
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    // ------------------------------------------------------------------ rutas

    private fun handleRegister(client: Socket, length: Int) {
        readBody(client, length)  // el peer nos envia su info; no la necesitamos aqui
        val json = JSONObject().apply {
            put("alias", android.os.Build.MODEL ?: "Android")
            put("version", "2.2")
            put("deviceModel", android.os.Build.MODEL)
            put("deviceType", "mobile")
            put("fingerprint", fingerprint())
            put("port", PORT)
            put("protocol", "http")
            put("download", true)
        }.toString()
        respondWithJson(client, 200, json)
    }

    /**
     * Por cada sesion guardamos que "id de archivo" corresponde a que nombre.
     * El protocolo identifica los archivos por un id opaco (file_0), NO por el
     * nombre: asi el nombre puede tener espacios o acentos sin romper la URL.
     * Mantener esta separacion es lo que nos hace compatibles con LocalSend real.
     */
    private val sessionFiles = mutableMapOf<String, Map<String, String>>()

    private fun handlePrepareUpload(client: Socket, length: Int) {
        val body = readBody(client, length) ?: run {
            respond(client, 400, "invalid"); return
        }
        try {
            val json = JSONObject(body)
            val alias = json.optJSONObject("info")?.optString("alias") ?: "Equipo"
            val files = json.optJSONObject("files")
            var total = 0L
            val idToName = linkedMapOf<String, String>()
            files?.keys()?.forEach { id ->
                val f = files.getJSONObject(id)
                idToName[id] = f.optString("fileName", "archivo")
                total += f.optLong("size", 0)
            }

            val sessionId = java.util.UUID.randomUUID().toString()
            sessionFiles[sessionId] = idToName
            acceptedSessions += sessionId
            onIncomingRequest?.invoke(sessionId, alias, total)

            val tokensJson = JSONObject()
            idToName.keys.forEach { id -> tokensJson.put(id, "tok_${sessionId.take(8)}_$id") }

            val response = JSONObject().apply {
                put("sessionId", sessionId)
                put("files", tokensJson)
            }.toString()
            Log.i(TAG, "Sesion aceptada de '$alias': ${idToName.size} archivo(s), $total bytes")
            respondWithJson(client, 200, response)
        } catch (e: Exception) {
            Log.e(TAG, "prepare-upload invalido: ${e.message}")
            respond(client, 400, "invalid")
        }
    }

    private fun handleUpload(client: Socket, path: String, length: Int) {
        val sessionId = path.substringAfter("sessionId=", "").substringBefore("&")
        val fileId = java.net.URLDecoder.decode(
            path.substringAfter("fileId=", "").substringBefore("&"), "UTF-8"
        )
        // Resolvemos el nombre real a partir del id de la sesion.
        val decodedName = sessionFiles[sessionId]?.get(fileId) ?: fileId.ifBlank { "archivo" }

        val out = File(receiveDir, uniqueName(decodedName))
        try {
            BufferedOutputStream(out.outputStream(), 64 * 1024).use { fileOut ->
                val buf = ByteArray(64 * 1024)
                var remaining = length.toLong()
                val input = client.getInputStream()
                while (remaining > 0) {
                    val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    fileOut.write(buf, 0, read)
                    remaining -= read
                }
            }
            Log.i(TAG, "Archivo recibido: ${out.name} (${out.length()} bytes)")
            respond(client, 200, "OK")
            // Publica una copia visible en Descargas (MediaStore) sin bloquear.
            publishToDownloads(out)
            onFileReceived?.invoke(out)
        } catch (e: Exception) {
            Log.e(TAG, "Fallo recibiendo $decodedName: ${e.message}")
            try { out.delete() } catch (_: Exception) {}
            try { respond(client, 500, "error") } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun readBody(client: Socket, length: Int): String? {
        if (length <= 0) return null
        val data = ByteArray(length)
        var read = 0
        val input = client.getInputStream()
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(data, 0, read)
    }

    private fun respond(client: Socket, code: Int, text: String) {
        val body = text.toByteArray()
        val header = "HTTP/1.1 $code OK$CRLF" +
            "Content-Type: text/plain$CRLF" +
            "Content-Length: ${body.size}$CRLF" +
            "Connection: close$CRLF$CRLF"
        val out = client.getOutputStream()
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun respondWithJson(client: Socket, code: Int, json: String) {
        val body = json.toByteArray()
        val header = "HTTP/1.1 $code OK$CRLF" +
            "Content-Type: application/json$CRLF" +
            "Content-Length: ${body.size}$CRLF" +
            "Connection: close$CRLF$CRLF"
        val out = client.getOutputStream()
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun infoJson(): String = JSONObject().apply {
        put("alias", android.os.Build.MODEL ?: "Android")
        put("version", "2.2")
        put("deviceModel", android.os.Build.MODEL)
        put("deviceType", "mobile")
        put("fingerprint", fingerprint())
        put("download", true)
    }.toString()

    private fun fingerprint(): String =
        context.getSharedPreferences("tb_prefs", Context.MODE_PRIVATE)
            .getString("fingerprint", null) ?: "unknown"

    /**
     * Copia el archivo recibido a Descargas para que sea visible desde la
     * Galeria y el gestor de archivos. Se usa MediaStore porque el acceso
     * directo a /storage/emulated/0/Download esta bloqueado (scoped storage).
     * Si falla, no es grave: el archivo original sigue en el almacenamiento
     * privado de la app y la recepcion se considera correcta.
     */
    private fun publishToDownloads(file: File) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, guessMime(file.name))
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/TransferBubble")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: run {
                Log.w(TAG, "MediaStore no devolvio URI para ${file.name}")
                return
            }
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "Copiado a Descargas/TransferBubble: ${file.name}")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo publicar en Descargas (el archivo sigue en privado): ${e.message}")
        }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    /** Evita sobrescribir: foto.jpg -> foto (1).jpg */
    private fun uniqueName(name: String): String {
        var candidate = File(receiveDir, name)
        if (!candidate.exists()) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(receiveDir, "$base ($i)$ext")
            i++
        }
        return candidate.name
    }
}