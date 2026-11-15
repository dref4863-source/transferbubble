package com.dref.transferbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.dref.transferbubble.net.Peer
import kotlin.math.abs

/**
 * FASE 4 - BURBUJA FLOTANTE CON BANDEJA.
 *
 * Una burbuja SIEMPRE encima de todo (overlay). Al tocarla se abre el panel
 * de la bandeja (ver PanelBandeja.kt) para elegir con checks QUE archivos
 * enviar y A QUE equipos; los equipos apagados quedan en espera y se envian
 * solos cuando aparecen.
 *
 * Por que NO se puede "soltar un archivo encima de la burbuja": Android aisla
 * las apps, asi que una ventana flotante no puede leer lo que hay debajo ni
 * aceptar un drag&drop de otra app. Lo que si se puede es ser la puerta de
 * entrada permanente: guardar en la bandeja y enviar cuando quieras.
 *
 * CAMBIOS DE ESTA VERSION (peticiones del usuario):
 *   1. Burbuja MAS PEQUEÑA (76dp -> 54dp): el icono era demasiado grande.
 *   2. Cruz "✕" en la esquina de la burbuja para CERRARLA por completo desde
 *      la propia burbuja (antes solo se podia desde los ajustes de la app).
 *   3. El bucle de red ya NO vive aqui: lo lleva Rastreo (objeto compartido).
 *      Asi la app tambien puede enviar con la burbuja apagada.
 */
class BurbujaService : Service() {

    companion object {
        private const val TAG = "TB-Burbuja"
        private const val CHANNEL_ID = "tb_burbuja"
        private const val NOTIF_ID = 1002

        @Volatile var activa = false
            private set

        fun start(context: Context) {
            val i = Intent(context, BurbujaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BurbujaService::class.java))
        }

        fun tienePermiso(context: Context): Boolean = Settings.canDrawOverlays(context)
    }

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

    private var contenedor: FrameLayout? = null
    private var burbuja: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: PanelBandeja? = null

    @Volatile private var peersVistos: List<Peer> = emptyList()

    /** Oyente del motor de rastreo, para poder darlo de baja al destruirnos. */
    private val oyenteRastreo: (List<Peer>) -> Unit = { lista ->
        peersVistos = lista
        ui.post {
            panel?.refrescar(Rastreo.aliasPropio(this), Rastreo.fingerprintPropio(this))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Servicio de burbuja creado")
        Bandeja.init(this)
        Bandeja.onCambio = { ui.post { refrescar() } }
        crearCanal()
        startForeground(NOTIF_ID, notificacion())
        activa = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Si la burbuja se enciende con el receptor apagado, se enciende tambien:
        // sin receptor no hay motor de red y los envios quedarian en espera.
        ReceiverService.start(this)
        Rastreo.observar(oyenteRastreo)
        if (Rastreo.activo) peersVistos = Rastreo.peers

        if (!tienePermiso(this)) {
            Log.w(TAG, "Sin permiso de superposicion: no se puede mostrar la burbuja")
            return
        }
        ui.post { mostrarBurbuja() }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Servicio de burbuja detenido")
        activa = false
        Rastreo.dejarDeObservar(oyenteRastreo)
        Bandeja.onCambio = null
        ui.post { quitarBurbuja(); cerrarPanel() }
        super.onDestroy()
    }

    private fun refrescar() {
        burbuja?.text = textoBurbuja()
        panel?.refrescar(Rastreo.aliasPropio(this), Rastreo.fingerprintPropio(this))
    }

    // ------------------------------------------------------------------ burbuja

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun circulo(color: Int, bordeDp: Int = 2) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(bordeDp), 0xFFFFFFFF.toInt())
    }

    private fun tipoVentana(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun textoBurbuja(): String {
        val n = Bandeja.items.size
        return if (n > 0) n.toString() else "TB"
    }

    private fun mostrarBurbuja() {
        if (contenedor != null) return
        val w = wm ?: return

        // PETICION DEL USUARIO: "el icono es un poco grande, hay que disminuirla".
        // Antes 76dp (y antes de eso 58dp) -> ahora 54dp, que sigue siendo
        // comodo para pulsar pero no tapa media pantalla.
        val tam = dp(54)
        val tamCruz = dp(22)

        // El contenedor lleva DOS cosas: la burbuja (abajo-izquierda) y una
        // pequeña cruz de cerrar (arriba-derecha). Al ser hijos del mismo
        // FrameLayout, los dos se mueven juntos al arrastrar y la cruz recibe
        // el toque antes que la burbuja (esta dibujada encima).
        val marco = FrameLayout(this)
        val bola = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            text = textoBurbuja()
            background = circulo(0xFF1565C0.toInt())
        }
        marco.addView(bola, FrameLayout.LayoutParams(tam, tam).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        })

        // Cruz de cierre: cierra la burbuja COMPLETAMENTE (y con ella el envio
        // por burbuja). Es la opcion que faltaba: antes habia que ir a la app.
        val cruz = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            text = "✕"
            background = circulo(0xFFC62828.toInt(), 1)
            elevation = dp(8).toFloat()
            contentDescription = "Cerrar la burbuja"
            setOnClickListener {
                Log.i(TAG, "Cerrando la burbuja desde la propia burbuja")
                stopSelf()
            }
        }
        marco.addView(cruz, FrameLayout.LayoutParams(tamCruz, tamCruz).apply {
            gravity = Gravity.TOP or Gravity.END
        })

        val p = WindowManager.LayoutParams(
            tam, tam, tipoVentana(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(220)
        }

        marco.setOnTouchListener(object : View.OnTouchListener {
            private var ini = 0L
            private var movido = false
            private var abajoX = 0f
            private var abajoY = 0f
            override fun onTouch(vista: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        ini = System.currentTimeMillis()
                        movido = false
                        abajoX = e.rawX
                        abajoY = e.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Umbral claro (18dp) para no confundir un toque con un arrastre.
                        if (abs(e.rawX - abajoX) > dp(18) || abs(e.rawY - abajoY) > dp(18)) movido = true
                        if (movido) {
                            p.x = (e.rawX.toInt() - tam / 2).coerceAtLeast(0)
                            p.y = (e.rawY.toInt() - tam / 2).coerceAtLeast(0)
                            try { w!!.updateViewLayout(vista, p) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!movido && System.currentTimeMillis() - ini < 900) abrirPanel()
                        return true
                    }
                }
                return false
            }
        })

        try {
            w.addView(marco, p)
            contenedor = marco
            burbuja = bola
            params = p
            Log.i(TAG, "Burbuja mostrada (${tam}px)")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mostrar la burbuja: ${e.message}")
        }
    }

    private fun quitarBurbuja() {
        contenedor?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        contenedor = null
        burbuja = null
    }

    // -------------------------------------------------------------------- panel

    private fun abrirPanel() {
        if (panel != null) { refrescar(); return }
        val w = wm ?: return
        val alto = resources.displayMetrics.heightPixels
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (alto * 0.82).toInt(),
            tipoVentana(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val creado = PanelBandeja(
            context = this,
            alCerrar = { cerrarPanel() },
            alEnviar = { archivos, equipos -> programar(archivos, equipos) },
            peersActuales = { peersVistos },
            // "Añadir" del panel: se abre el selector del sistema y lo elegido
            // entra en la bandeja. Hace falta una activity porque desde una
            // ventana de superposicion no se puede recoger el resultado.
            alElegir = {
                try {
                    val i = Intent(this, ElegirArchivosActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudo abrir el selector: ${e.message}")
                }
            },
            // "Salir" del panel: cierra la burbuja por completo (igual que la cruz).
            alCerrarTodo = {
                Log.i(TAG, "Cerrando la burbuja desde el panel")
                cerrarPanel()
                stopSelf()
            }
        )
        try {
            w.addView(creado.vista, p)
            panel = creado
            refrescar()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el panel: ${e.message}")
        }
    }

    private fun cerrarPanel() {
        panel?.let { try { wm?.removeView(it.vista) } catch (_: Exception) {} }
        panel = null
    }

    private fun programar(archivos: List<String>, equipos: List<Destino>): Int {
        val n = Bandeja.programarEnvios(archivos, equipos)
        Log.i(TAG, "Programados $n envio(s)")
        // Ciclo inmediato del motor compartido (no hay que esperar 7 s).
        Rastreo.ahora(this)
        ui.post { refrescar() }
        return n
    }

    // ------------------------------------------------------------- notificacion

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val c = NotificationChannel(
                CHANNEL_ID, "Burbuja TransferBubble", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Burbuja flotante para enviar desde cualquier app" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(c)
        }
    }

    private fun notificacion(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setContentTitle("Burbuja activa")
            .setContentText("Toca la burbuja para enviar archivos")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()
    }
}