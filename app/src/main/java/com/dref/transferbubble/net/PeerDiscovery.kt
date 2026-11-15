package com.dref.transferbubble.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Descubrimiento de equipos en la red local.
 *
 * Implementa la parte de descubrimiento del protocolo LocalSend v2.2:
 *  - Multicast UDP a 224.0.0.167:53317 con {"announce": true}
 *  - Fallback: barrido HTTP de la subred enviando POST /register a cada IP.
 *
 * Esto es lo que resuelve el problema detectado en las pruebas: la IP del
 * otro equipo cambia (DHCP), asi que NUNCA se asume una IP fija.
 */
class PeerDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "TB-Discovery"
        const val MULTICAST_GROUP = "224.0.0.167"
        const val PORT = 53317
        private const val ANNOUNCE_TIMEOUT_MS = 3000
    }

    /** Identificador estable de este equipo (fingerprint del protocolo). */
    val selfFingerprint: String by lazy {
        val prefs = context.getSharedPreferences("tb_prefs", Context.MODE_PRIVATE)
        prefs.getString("fingerprint", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("fingerprint", it).apply()
        }
    }

    val deviceAlias: String
        get() = android.os.Build.MODEL ?: "Android"

    /** Bloquea hasta ANNOUNCE_TIMEOUT_MS y devuelve los equipos encontrados. */
    fun discover(): List<Peer> {
        val found = linkedMapOf<String, Peer>()
        multicastDiscover(found)
        if (found.isEmpty()) {
            Log.i(TAG, "Multicast sin resultados, probando barrido de subred")
            subnetScan(found)
        }
        Log.i(TAG, "Descubiertos ${found.size} equipos: ${found.keys}")
        return found.values.toList()
    }

    // ---------------------------------------------------------------- multicast

    private fun multicastDiscover(found: MutableMap<String, Peer>) {
        var lock: WifiManager.MulticastLock? = null
        var socket: DatagramSocket? = null
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            // Android exige este lock para recibir paquetes multicast.
            lock = wifi?.createMulticastLock("tb_multicast")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            socket = DatagramSocket(PORT).apply {
                broadcast = true
                soTimeout = 500
                reuseAddress = true
            }

            val group = InetAddress.getByName(MULTICAST_GROUP)
            val announce = JSONObject().apply {
                put("alias", deviceAlias)
                put("version", "2.2")
                put("deviceModel", android.os.Build.MODEL)
                put("deviceType", "mobile")
                put("fingerprint", selfFingerprint)
                put("port", PORT)
                put("protocol", "http")
                put("download", false)
                put("announce", true)
            }.toString().toByteArray()

            val packet = DatagramPacket(announce, announce.size, group, PORT)
            socket.send(packet)          // primera emision
            Log.i(TAG, "Anuncio multicast enviado a $MULTICAST_GROUP:$PORT")

            val deadline = System.currentTimeMillis() + ANNOUNCE_TIMEOUT_MS
            var lastResend = System.currentTimeMillis()
            val buffer = ByteArray(8192)

            while (System.currentTimeMillis() < deadline) {
                // reenviamos el anuncio cada segundo: si el otro equipo arranco
                // despues, igual nos escucha.
                if (System.currentTimeMillis() - lastResend > 1000) {
                    socket.send(packet)
                    lastResend = System.currentTimeMillis()
                }
                try {
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    val ip = response.address.hostAddress ?: continue
                    val json = JSONObject(String(response.data, 0, response.length))
                    // Ignoramos nuestro propio anuncio.
                    if (json.optString("fingerprint") == selfFingerprint) continue
                    val peer = Peer(
                        alias = json.optString("alias", "Equipo"),
                        deviceModel = json.optString("deviceModel").takeIf { it.isNotBlank() },
                        deviceType = json.optString("deviceType").takeIf { it.isNotBlank() },
                        fingerprint = json.optString("fingerprint", ip),
                        port = json.optInt("port", PORT),
                        protocol = json.optString("protocol", "http"),
                        ip = ip,
                        version = json.optString("version", "2.2")
                    )
                    found[peer.fingerprint] = peer
                    Log.i(TAG, "Equipo encontrado por multicast: ${peer.displayName} @ $ip")
                } catch (_: SocketTimeoutException) {
                    // normal, seguimos esperando hasta el deadline
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo en multicast: ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            try { lock?.release() } catch (_: Exception) {}
        }
    }

    // -------------------------------------------------------------- subnet scan

    /** Fallback: prueba POST /register contra cada IP de la subred, en paralelo. */
    private fun subnetScan(found: MutableMap<String, Peer>) {
        val base = localSubnetPrefix() ?: return
        val jobs = mutableListOf<Thread>()
        val lock = Object()

        for (i in 1..254) {
            val ip = "$base.$i"
            if (ip == localIp()) continue
            val t = thread(start = true) {
                probeRegister(ip)?.let { peer ->
                    synchronized(lock) { found[peer.fingerprint] = peer }
                }
            }
            jobs += t
        }
        jobs.forEach { it.join(1500) }
    }

    private fun probeRegister(ip: String): Peer? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, PORT), 350)
            socket.soTimeout = 600

            val body = JSONObject().apply {
                put("alias", deviceAlias)
                put("version", "2.2")
                put("deviceModel", android.os.Build.MODEL)
                put("deviceType", "mobile")
                put("fingerprint", selfFingerprint)
                put("port", PORT)
                put("protocol", "http")
                put("download", false)
            }.toString()

            val out = socket.getOutputStream()
            out.write(
                ("POST /api/localsend/v2/register HTTP/1.1\r\n" +
                 "Host: $ip:$PORT\r\n" +
                 "Content-Type: application/json\r\n" +
                 "Content-Length: ${body.toByteArray().size}\r\n" +
                 "Connection: close\r\n\r\n" + body).toByteArray()
            )
            out.flush()

            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            if (!response.contains("200")) return null
            val jsonPart = response.substringAfter("\r\n\r\n", "")
            if (jsonPart.isBlank()) return null

            val json = JSONObject(jsonPart)
            if (json.optString("fingerprint") == selfFingerprint) return null
            Peer(
                alias = json.optString("alias", "Equipo"),
                deviceModel = json.optString("deviceModel").takeIf { it.isNotBlank() },
                deviceType = json.optString("deviceType").takeIf { it.isNotBlank() },
                fingerprint = json.optString("fingerprint", ip),
                port = json.optInt("port", PORT),
                protocol = json.optString("protocol", "http"),
                ip = ip,
                version = json.optString("version", "2.2")
            )
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ helpers

    fun localIp(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    private fun localSubnetPrefix(): String? = localIp()?.substringBeforeLast('.')
}