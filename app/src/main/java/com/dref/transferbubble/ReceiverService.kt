package com.dref.transferbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.dref.transferbubble.net.HttpReceiver

/**
 * Servicio en primer plano que mantiene el receptor SIEMPRE activo.
 *
 * MOTIVO (Fase 2): antes el servidor HTTP vivia dentro de MainActivity, asi que
 * al cerrar la app o mandarla a segundo plano dejaba de recibir archivos.
 * Esa era la limitacion mas molesta del MVP.
 *
 * Ahora el receptor se ejecuta aqui, con una notificacion permanente que
 * Android exige para los servicios en primer plano. Resultado: el equipo
 * aparece en la red y RECIBE aunque la pantalla este apagada o la app cerrada.
 *
 * Nota: a partir de Android 14 (API 34) hay que declarar el tipo de servicio
 * de datos (foregroundServiceType="dataSync") en el manifiesto.
 */
class ReceiverService : Service() {

    companion object {
        private const val TAG = "TB-Service"
        private const val CHANNEL_ID = "tb_receiver"
        private const val NOTIF_ID = 1001

        /** Estado compartido para que la interfaz muestre que esta activo. */
        @Volatile var isRunning = false
            private set

        /** Callback para que la UI se entere de los archivos recibidos. */
        @Volatile var onFileReceived: ((String) -> Unit)? = null

        fun start(context: Context) {
            val i = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }

    private var receiver: HttpReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Servicio creado")
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Escuchando en la red local"))
        startReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startReceiver()
        // START_STICKY: si Android lo mata por memoria, lo relanza.
        return START_STICKY
    }

    private fun startReceiver() {
        if (receiver != null) return
        receiver = HttpReceiver(this).apply {
            onFileReceived = { file ->
                Log.i(TAG, "Archivo recibido: ${file.name} (${file.length()} bytes)")
                // OJO: hay que cualificar con Companion. Dentro de este apply(),
                // "onFileReceived" se resuelve al de HttpReceiver (que espera un
                // File) y provocaba: "Argument type mismatch: actual type is
                // 'kotlin.String!', but 'java.io.File' was expected".
                // El que queremos avisar es el observador del servicio (String).
                Companion.onFileReceived?.invoke(file.name)
                updateNotification("Recibido: ${file.name}")
            }
            start()
        }
        isRunning = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Servicio detenido")
        receiver?.stop()
        receiver = null
        isRunning = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notificacion

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Receptor TransferBubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mantiene el equipo visible para recibir archivos" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("TransferBubble activo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}