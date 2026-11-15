package com.dref.transferbubble

import android.content.Context
import android.util.Log
import com.dref.transferbubble.net.Peer
import com.dref.transferbubble.net.PeerDiscovery
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * MOTOR DE DESCUBRIMIENTO Y ENVIO PERMANENTE.
 *
 * CAUSA RAIZ DEL FALLO REPORTADO
 * ("los archivos solo se pueden enviar desde la burbuja, no desde el panel principal"):
 * antes el bucle de red vivia DENTRO de BurbujaService. Si la burbuja estaba
 * apagada, nadie llamaba a Bandeja.procesar(), asi que las tareas se quedaban
 * en ESPERANDO_EQUIPO para siempre y el boton "Enviar lo marcado" de la app
 * parecia no hacer nada.
 *
 * Ahora el motor vive aqui y lo arranca el RECEPTOR (el servicio en primer
 * plano que siempre esta vivo, con o sin burbuja). Ventajas:
 *   - La app puede enviar sin la burbuja activa.
 *   - El envio diferido sigue funcionando con la app cerrada.
 *   - No se duplica el trabajo de red (antes, burbuja + app podian crear dos bucles).
 *
 * Cualquier pantalla puede consultar Rastreo.peers o suscribirse con observar().
 */
object Rastreo {

    private const val TAG = "TB-Rastreo"
    private const val CADA_MS = 7000L

    /** Ultima lista de equipos encontrada (la leen la app y la burbuja). */
    @Volatile var peers: List<Peer> = emptyList()
        private set

    @Volatile var activo = false
        private set

    private val oyentes = CopyOnWriteArrayList<(List<Peer>) -> Unit>()

    private var discovery: PeerDiscovery? = null
    private var hilo: Thread? = null

    /** Suscribe a quien quiera enterarse de las listas nuevas (app, burbuja). */
    fun observar(oyente: (List<Peer>) -> Unit) {
        if (!oyentes.contains(oyente)) oyentes.add(oyente)
    }

    fun dejarDeObservar(oyente: (List<Peer>) -> Unit) {
        oyentes.remove(oyente)
    }

    fun iniciar(context: Context) {
        if (activo) return
        val app = context.applicationContext
        discovery = PeerDiscovery(app)
        activo = true
        hilo = thread(start = true, name = "tb-rastreo") {
            Log.i(TAG, "Motor de rastreo iniciado")
            while (activo) {
                try {
                    ciclo(app)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en el rastreo: ${e.message}")
                }
                try { Thread.sleep(CADA_MS) } catch (_: InterruptedException) { }
            }
            Log.i(TAG, "Motor de rastreo detenido")
        }
    }

    fun detener() {
        activo = false
        hilo?.interrupt()
        hilo = null
        discovery = null
        peers = emptyList()
    }

    /**
     * Fuerza un ciclo AHORA (se llama al pulsar Enviar, para no esperar 7 s) y
     * ademas redescubre, de modo que si el equipo acaba de aparecer se envia ya.
     */
    fun ahora(context: Context) {
        val app = context.applicationContext
        thread(start = true, name = "tb-rastreo-ya") {
            try { ciclo(app) } catch (_: Exception) {}
        }
    }

    /** Datos de este equipo (los usa la burbuja para presentarse en la red). */
    fun aliasPropio(context: Context): String =
        (discovery ?: PeerDiscovery(context.applicationContext).also { discovery = it }).deviceAlias

    fun fingerprintPropio(context: Context): String =
        (discovery ?: PeerDiscovery(context.applicationContext).also { discovery = it }).selfFingerprint

    /** Un ciclo: buscar equipos, avisar a las interfaces y disparar lo pendiente. */
    private fun ciclo(context: Context) {
        val d = discovery ?: return
        val encontrados = d.discover()
        peers = encontrados
        oyentes.forEach { o ->
            try { o(encontrados) } catch (_: Exception) {}
        }
        // ESTO es lo que hace que el envio diferido funcione tambien sin burbuja.
        Bandeja.procesar(
            context = context,
            peers = encontrados,
            aliasPropio = d.deviceAlias,
            fingerprintPropio = d.selfFingerprint
        )
    }
}
