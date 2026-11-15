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
import android.widget.TextView
import com.dref.transferbubble.net.Peer
import com.dref.transferbubble.net.PeerDiscovery
import kotlin.concurrent.thread
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
 */
class BurbujaService : Service() {

    companion object {
        private const val TAG = "TB-Burbuja"
        private const val CHANNEL_ID = "tb_burbuja"
        private const val NOTIF_ID = 1002
        private const val RASTREO_MS = 7000L

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
    private lateinit var discovery: PeerDiscovery

    private var burbuja: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: PanelBandeja? = null

    @Volatile private var peersVistos: List<Peer> = emptyList()
    @Volatile private var sigo = true

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Servicio de burbuja creado")
        Bandeja.init(this)
        Bandeja.onCambio = { ui.post { refrescar() } }
        discovery = PeerDiscovery(this)
        crearCanal()
        startForeground(NOTIF_ID, notificacion())
        activa = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (!tienePermiso(this)) {
            Log.w(TAG, "Sin permiso de superposicion: no se puede mostrar la burbuja")
            return
        }
        ui.post { mostrarBurbuja() }
        iniciarRastreo()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Servicio de burbuja detenido")
        activa = false
        sigo = false
        Bandeja.onCambio = null
        ui.post { quitarBurbuja(); cerrarPanel() }
        super.onDestroy()
    }

    private fun refrescar() {
        burbuja?.text = textoBurbuja()
        panel?.refrescar(discovery.deviceAlias, discovery.selfFingerprint)
    }

    // ------------------------------------------------------------------ burbuja

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun circulo(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), 0xFFFFFFFF.toInt())
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
        if (burbuja != null) return
        val w = wm ?: return
        // BUG CORREGIDO ("no se puede pulsar facil, se escurre"):
        // antes eran 58dp y el arrastre se activaba con solo 10dp de movimiento,
        // asi que el mas minimo temblor del dedo contaba como arrastre y el
        // panel no se abria nunca. Ahora la burbuja es mas grande y hace falta
        // un desplazamiento claro para arrastrarla.
        val tam = dp(76)

        val v = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            text = textoBurbuja()
            background = circulo(0xFF1565C0.toInt())
            elevation = dp(6).toFloat()
        }

        val p = WindowManager.LayoutParams(
            tam, tam, tipoVentana(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(10)
            y = dp(220)
        }

        v.setOnTouchListener(object : View.OnTouchListener {
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
                        // Umbral claro (22dp) para no confundir un toque con un arrastre.
                        if (abs(e.rawX - abajoX) > dp(22) || abs(e.rawY - abajoY) > dp(22)) movido = true
                        if (movido) {
                            p.x = (e.rawX.toInt() - tam / 2).coerceAtLeast(0)
                            p.y = (e.rawY.toInt() - tam / 2).coerceAtLeast(0)
                            try { w!!.updateViewLayout(vista, p) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Se abre con un toque corto, o con pulsacion algo larga
                        // sin desplazamiento (mas tolerante que antes).
                        if (!movido && System.currentTimeMillis() - ini < 900) abrirPanel()
                        return true
                    }
                }
                return false
            }
        })

        try {
            w.addView(v, p)
            burbuja = v
            params = p
            Log.i(TAG, "Burbuja mostrada")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mostrar la burbuja: ${e.message}")
        }
    }

    private fun quitarBurbuja() {
        burbuja?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        burbuja = null
    }

    // -------------------------------------------------------------------- panel

    private fun abrirPanel() {
        if (panel != null) { refrescar(); return }
        val w = wm ?: return
        val alto = resources.displayMetrics.heightPixels
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (alto * 0.78).toInt(),
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
        // Intento inmediato y refresco del panel.
        ui.post {
            thread { lanzarProcesado() }
            refrescar()
        }
        return n
    }

    // ------------------------------------------------------------------ rastreo

    private fun iniciarRastreo() {
        thread {
            while (sigo) {
                try {
                    lanzarProcesado()
                } catch (e: Exception) {
                    Log.e(TAG, "Error en rastreo: ${e.message}")
                }
                try { Thread.sleep(RASTREO_MS) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun lanzarProcesado() {
        val peers = discovery.discover()
        peersVistos = peers
        // Los equipos que ya no estan conectados se muestran como "desconectado".
        ui.post {
            panel?.refrescar(discovery.deviceAlias, discovery.selfFingerprint)
            // Envia automaticamente lo que esperaba a algun equipo que aparecio.
            Bandeja.procesar(
                context = applicationContext,
                peers = peers,
                aliasPropio = discovery.deviceAlias,
                fingerprintPropio = discovery.selfFingerprint
            )
        }
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