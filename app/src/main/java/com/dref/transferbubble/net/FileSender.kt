package com.dref.transferbubble.net

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Envio de archivos siguiendo el protocolo LocalSend v2.2:
 *   1. POST /api/localsend/v2/prepare-upload  -> el receptor acepta y da tokens
 *   2. POST /api/localsend/v2/upload          -> se envia el binario
 *
 * CORRECCIONES tras la prueba real (bug "no se comparte nada"):
 *
 *  BUG 1: se abria el archivo con File(Uri.parse(uri).path). Los URIs que
 *  llegan de la Galeria / del menu Compartir son content:// y NO tienen ruta
 *  de fichero real, asi que src.exists() daba false y el envio se abortaba en
 *  silencio. Ahora se lee SIEMPRE con context.contentResolver.openInputStream()
 *  y solo se usa File como respaldo para URIs file://.
 *
 *  BUG 2: en la query de /upload se mandaba "fileId=<nombre del archivo>" en
 *  lugar del id del protocolo (file_0). El receptor busca el nombre a partir
 *  del id, asi que hay que enviar el id. Ahora se envia el id correcto.
 *
 *  BUG 3: si el tamano no se podia determinar (0), setFixedLengthStreamingMode(0)
 *  enviaba cero bytes. Ahora se resuelve el tamano real desde el ContentResolver
 *  y se usa modo chunked si sigue siendo desconocido.
 */
class FileSender(private val context: Context) {

    companion object {
        private const val TAG = "TB-Sender"
        private const val BUFFER = 64 * 1024
    }

    /** Tokens por sesion: sessionId -> (id de archivo -> nombre de archivo). */
    private class Session(
        val id: String,
        val tokenById: Map<String, String>,
        val idByName: Map<String, String>
    )

    private var lastSession: Session? = null

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Envia los archivos al equipo indicado.
     * @param onProgress recibe el progreso agregado, para la UI.
     * @return true si TODOS los archivos se enviaron correctamente.
     */
    fun send(
        peer: Peer,
        files: List<FileToSend>,
        senderAlias: String,
        senderFingerprint: String,
        onProgress: (TransferProgress) -> Unit = {}
    ): Boolean {
        if (files.isEmpty()) {
            Log.w(TAG, "No hay archivos que enviar")
            return false
        }
        return try {
            val session = prepareUpload(peer, files, senderAlias, senderFingerprint)
            if (session == null) {
                Log.e(TAG, "El receptor rechazo la sesion (prepare-upload)")
                return false
            }
            lastSession = session
            val totalBytes = files.sumOf { it.size }
            var sentBytes = 0L

            for (f in files) {
                val fileId = session.idByName[f.fileName]
                val token = session.tokenById[fileId ?: ""]
                if (fileId == null || token == null) {
                    Log.e(TAG, "Sin token para '${f.fileName}'; se omite")
                    continue
                }
                val ok = uploadOne(peer, session.id, fileId, token, f) { localSent ->
                    onProgress(TransferProgress(f.fileName, sentBytes + localSent, totalBytes))
                }
                if (!ok) {
                    Log.e(TAG, "Fallo al subir '${f.fileName}'")
                    return false
                }
                sentBytes += f.size
            }
            Log.i(TAG, "Envio completado: ${files.size} archivo(s), $sentBytes bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------ prepare-upload

    private fun prepareUpload(
        peer: Peer,
        files: List<FileToSend>,
        senderAlias: String,
        senderFingerprint: String
    ): Session? {
        val filesJson = JSONObject()
        files.forEachIndexed { i, f ->
            val id = "file_$i"
            filesJson.put(id, JSONObject().apply {
                put("id", id)
                put("fileName", f.fileName)
                put("size", f.size)
                put("fileType", f.mimeType)
            })
        }
        // nombre -> id (para saber que id usar al subir cada archivo)
        val idByName = linkedMapOf<String, String>()
        files.forEachIndexed { i, f -> idByName[f.fileName] = "file_$i" }

        val body = JSONObject().apply {
            put("info", JSONObject().apply {
                put("alias", senderAlias)
                put("version", "2.2")
                put("deviceModel", android.os.Build.MODEL)
                put("deviceType", "mobile")
                put("fingerprint", senderFingerprint)
                put("port", Peer.DEFAULT_PORT)
                put("protocol", "http")
                put("download", false)
            })
            put("files", filesJson)
        }.toString()

        val conn = (URL("${peer.baseUrl}/api/localsend/v2/prepare-upload").openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        Log.i(TAG, "prepare-upload -> HTTP $code")
        if (code != 200) return null

        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val sessionId = json.optString("sessionId", "")
        val tokensJson = json.optJSONObject("files") ?: return null

        val tokenById = mutableMapOf<String, String>()
        tokensJson.keys().forEach { id -> tokenById[id] = tokensJson.optString(id) }

        return Session(sessionId, tokenById, idByName)
    }

    // ------------------------------------------------------------------- upload

    private fun uploadOne(
        peer: Peer,
        sessionId: String,
        fileId: String,
        token: String,
        file: FileToSend,
        onBytes: (Long) -> Unit
    ): Boolean {
        // Tamano real: si la app que comparte no lo dio, se consulta al resolver.
        val size = if (file.size > 0) file.size else resolveSize(file.uri)
        val stream = openStream(file.uri)
        if (stream == null) {
            Log.e(TAG, "No se pudo abrir el archivo: ${file.uri}")
            return false
        }

        val url = URL(
            "${peer.baseUrl}/api/localsend/v2/upload?sessionId=${enc(sessionId)}" +
            "&fileId=${enc(fileId)}&token=${enc(token)}"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 60000
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        if (size > 0) conn.setFixedLengthStreamingMode(size) else conn.setChunkedStreamingMode(BUFFER)

        var sent = 0L
        try {
            BufferedOutputStream(conn.outputStream, BUFFER).use { out ->
                stream.use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        sent += read
                        onBytes(sent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Corte durante la subida de '${file.fileName}': ${e.message}")
            return false
        }

        val code = try { conn.responseCode } catch (e: Exception) { -1 }
        Log.i(TAG, "upload '${file.fileName}' -> HTTP $code ($sent bytes)")
        return code in 200..299
    }

    /**
     * Abre el contenido sea cual sea el tipo de URI.
     * content:// -> ContentResolver (caso real de la Galeria / Compartir)
     * file://    -> File como respaldo
     */
    private fun openStream(uri: String): InputStream? = try {
        val u = Uri.parse(uri)
        when (u.scheme) {
            "content", "android.resource" -> context.contentResolver.openInputStream(u)
            "file" -> u.path?.let { java.io.File(it).inputStream() }
            else -> context.contentResolver.openInputStream(u) ?: u.path?.let { java.io.File(it).inputStream() }
        }
    } catch (e: Exception) {
        Log.e(TAG, "openStream fallo para ${uri}: ${e.message}")
        null
    }

    /** Tamano real del archivo a partir del ContentResolver. */
    private fun resolveSize(uri: String): Long = try {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length } ?: 0L
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo resolver el tamano de ${uri}: ${e.message}")
        0L
    }

    /** Cancela la sesion en curso si el usuario aborta. */
    fun cancel(peer: Peer) {
        val s = lastSession ?: return
        try {
            val conn = (URL("${peer.baseUrl}/api/localsend/v2/cancel?sessionId=${enc(s.id)}")
                .openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.responseCode
        } catch (_: Exception) {}
    }
}