package com.dref.transferbubble.net

/**
 * Participante descubierto en la red local.
 * Corresponde a un mensaje /register del protocolo LocalSend v2.2.
 */
data class Peer(
    val alias: String,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String,
    val port: Int = DEFAULT_PORT,
    val protocol: String = "http",
    /** IP desde la que respondio. Se resuelve al descubrir, no viene en el JSON. */
    val ip: String,
    val version: String = "2.2"
) {
    /** Direccion base para las llamadas REST. */
    val baseUrl: String
        get() = "$protocol://$ip:$port"

    /** Texto que se muestra en la lista de equipos. */
    val displayName: String
        get() = if (deviceModel.isNullOrBlank()) alias else "$alias ($deviceModel)"

    companion object {
        const val DEFAULT_PORT = 53317
    }
}

/** Archivo a enviar con su tamano ya resuelto. */
data class FileToSend(
    val uri: String,
    val fileName: String,
    val size: Long,
    val mimeType: String = "application/octet-stream"
)

/** Progreso de una transferencia, para la UI y la notificacion. */
data class TransferProgress(
    val fileName: String,
    val bytesSent: Long,
    val totalBytes: Long
) {
    val percent: Int
        get() = if (totalBytes <= 0) 0 else ((bytesSent * 100) / totalBytes).toInt().coerceIn(0, 100)
}