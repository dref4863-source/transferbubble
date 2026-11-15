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
 * Fase 2: permite recibir archivos con la app cerrada.
 *
 * Fase 3 (esta version): la notificacion permanente ahora trae BOTONES para
 * pausar/reanudar y detener por completo, como pidio el usuario.
 *
 *   - PAUSAR   -> cierra el socket. El equipo desaparece de la red y deja de
 *                 recibir. La notificacion sigue ahi con "Reanudar" para poder
 *                 volver a activarlo con un toque.
 *   - DETENER  -> apaga el servicio por completo y quita la notificacion.
 *
 * Nota: a partir de Android 14 (API 34) hay que declarar el tipo de servicio
 * de datos (foregroundServiceType="dataSync") en el manifiesto.
 */
class ReceiverService : Service() {

    companion object {
        private const val TAG = "TB-Service"
        private const val CHANNEL_ID = "tb_receiver"
        private const val NOTIF_ID = 1001

        const val ACTION_PAUSE = "com.dref.transferbubble.PAUSE"
        const val ACTION_RESUME = "com.dref.transferbubble.RESUME"
        const val ACTION_STOP = "com.dref.transferbubble.STOP"

        /** Estado compartido para que la interfaz muestre que esta activo. */
        @Volatile var isRunning = false
            private set

        /** true = el usuario lo pauso a proposito (equipo oculto en la red). */
        @Volatile var isPaused = false
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

        /** Manda una orden al servicio ya en marcha (pausar/reanudar/detener). */
        fun send(context: Context, action: String) {
            val i = Intent(context, ReceiverService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    private var receiver: HttpReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Servicio creado")
        createChannel()
        // Nos declaramos en primer plano ANTES de cualquier otra cosa: Android
        // da ~5 segundos o mata el proceso.
        startForeground(NOTIF_ID, buildNotification())
        startReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopEverything()
            // Sin accion = arranque normal (la app se abrio).
            // Si el usuario lo habia pausado a proposito, se respeta la pausa:
            // no tiene sentido que abrir la app lo reactive sin querer.
            else -> if (isPaused) updateNotification() else startReceiver()
        }
        // START_STICKY: si Android lo mata por memoria, lo relanza.
        return START_STICKY
    }

    // ------------------------------------------------------------------ acciones

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
        isPaused = false
    }

    /** Pausa la recepcion: cierra el puerto de red, pero deja la notificacion. */
    private fun pause() {
        Log.i(TAG, "Pausando recepcion")
        receiver?.stop()
        receiver = null
        isRunning = false
        isPaused = true
        updateNotification()
    }

    /** Vuelve a activar la recepcion. */
    private fun resume() {
        Log.i(TAG, "Reanudando recepcion")
        isPaused = false
        startReceiver()
        updateNotification()
    }

    /** Apaga el servicio por completo (y quita la notificacion). */
    private fun stopEverything() {
        Log.i(TAG, "Deteniendo el servicio por completo")
        receiver?.stop()
        receiver = null
        isRunning = false
        isPaused = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
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

    /** PendingIntent hacia el propio servicio, con una accion concreta. */
    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val i = Intent(this, ReceiverService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(extra: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title: String
        val text: String
        if (isPaused) {
            title = "TransferBubble en pausa"
            text = "No se reciben archivos. Toca para volver a activar."
        } else {
            title = "TransferBubble activo"
            text = extra ?: "Escuchando en la red local"
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val icon = if (isPaused) android.R.drawable.ic_media_pause
                   else android.R.drawable.stat_sys_download_done

        builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(open)
            .setOngoing(true)

        // Botones de control (lo pedido: pausar / detener).
        if (isPaused) {
            builder.addAction(
                android.R.drawable.ic_media_play, "Reanudar", serviceAction(ACTION_RESUME, 2)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_pause, "Pausar", serviceAction(ACTION_PAUSE, 1)
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel, "Detener", serviceAction(ACTION_STOP, 3)
        )

        return builder.build()
    }

    private fun updateNotification(extra: String? = null) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification(extra))
        } catch (_: Exception) {}
    }
}