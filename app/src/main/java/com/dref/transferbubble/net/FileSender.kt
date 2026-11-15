package com.dref.transferbubble.net

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Envio de archivos siguiendo el protocolo LocalSend v2.2 (API de subida):
 *   1. POST /api/localsend/v2/prepare-upload  -> el receptor acepta y da tokens
 *   2. POST /api/localsend/v2/upload          -> se envia el binario
 *
 * Nota: el receptor debe tener la app abierta/aceptando, igual que LocalSend.
 */
class FileSender(private val context: Context) {

    companion object {
        private const val TAG = "TB-Sender"
        private const val BUFFER = 64 * 1024
    }

    /**
     * Envia los archivos al equipo indicado.
     * @param onProgress recibe el progreso agregado, para UI/notificacion.
     */
    fun send(
        peer: Peer,
        files: List<FileToSend>,
        senderAlias: String,
        senderFingerprint: String,
        onProgress: (TransferProgress) -> Unit = {}
    ): Boolean {
        if (files.isEmpty()) return false
        return try {
            val sessionId = prepareUpload(peer, files, senderAlias, senderFingerprint)
                ?: return false
            Log.i(TAG, "Sesion creada: $sessionId")

            val totalBytes = files.sumOf { it.size }
            var sentBytes = 0L

            for (file in files) {
                val token = tokens[sessionId]?.get(file.fileName) ?: continue
                val ok = uploadOne(peer, sessionId, file, token) { localSent ->
                    onProgress(
                        TransferProgress(
                            fileName = file.fileName,
                            bytesSent = sentBytes + localSent,
                            totalBytes = totalBytes
                        )
                    )
                }
                if (!ok) {
                    Log.e(TAG, "Fallo al subir ${file.fileName}")
                    return false
                }
                sentBytes += file.size
            }
            Log.i(TAG, "Envio completado: ${files.size} archivo(s)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando: ${e.message}")
            false
        }
    }

    /** Guarda los tokens que el receptor devuelve por cada archivo. */
    private val tokens = mutableMapOf<String, MutableMap<String, String>>()

    /** Respuesta del receptor resumida para el emisor. */
    private fun prepareUpload(
        peer: Peer,
        files: List<FileToSend>,
        senderAlias: String,
        senderFingerprint: String
    ): String? {
        val filesJson = JSONObject()
        files.forEachIndexed { index, f ->
            val id = "file_$index"
            filesJson.put(id, JSONObject().apply {
                put("id", id)
                put("fileName", f.fileName)
                put("size", f.size)
                put("fileType", f.mimeType)
            })
        }
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
        conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        Log.i(TAG, "prepare-upload -> HTTP $code")
        if (code != 200) return null

        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val sessionId = json.optString("sessionId", "")
        val fileTokens = json.optJSONObject("files") ?: return null

        val map = mutableMapOf<String, String>()
        fileTokens.keys().forEach { id ->
            val idx = id.removePrefix("file_").toIntOrNull()
            if (idx != null && idx < files.size) {
                map[files[idx].fileName] = fileTokens.optString(id)
            }
        }
        tokens[sessionId] = map
        return sessionId
    }

    private fun uploadOne(
        peer: Peer,
        sessionId: String,
        file: FileToSend,
        token: String,
        onBytes: (Long) -> Unit
    ): Boolean {
        val src = File(android.net.Uri.parse(file.uri).path ?: return false)
        if (!src.exists()) {
            Log.e(TAG, "No existe el archivo local: ${file.uri}")
            return false
        }
        val url = URL("${peer.baseUrl}/api/localsend/v2/upload?sessionId=$sessionId&fileId=${file.fileName}&token=$token")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        conn.setFixedLengthStreamingMode(file.size)
        conn.setRequestProperty("Content-Type", "application/octet-stream")

        var sent = 0L
        BufferedOutputStream(conn.outputStream, BUFFER).use { out ->
            src.inputStream().use { input ->
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
        val code = conn.responseCode
        Log.i(TAG, "upload ${file.fileName} -> HTTP $code ($sent bytes)")
        return code in 200..299
    }

    /** Cancela una sesion si el usuario aborta. */
    fun cancel(peer: Peer, sessionId: String) {
        try {
            val conn = (URL("${peer.baseUrl}/api/localsend/v2/cancel?sessionId=$sessionId")
                .openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.responseCode
        } catch (_: Exception) {}
    }
}