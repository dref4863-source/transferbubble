package com.dref.transferbubble

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dref.transferbubble.net.Peer

/**
 * PANEL DE LA BANDEJA (Fase 4).
 *
 * Es la interfaz que se abre al tocar la burbuja. Tiene dos listas con checks:
 *   1. Archivos de la bandeja  -> cuales enviar
 *   2. Equipos                 -> a cuales enviar
 * Y un boton ENVIAR que crea las tareas. Los equipos desconectados se quedan
 * en espera y se envian solos cuando aparecen en la red.
 *
 * Hecho con vistas nativas (no Compose) porque vive dentro de una ventana de
 * superposicion, donde Compose necesita inyectar un ciclo de vida y resulta
 * fragil. Con vistas normales funciona siempre.
 */
class PanelBandeja(
    private val context: Context,
    private val alCerrar: () -> Unit,
    private val alEnviar: (List<String>, List<Destino>) -> Int,
    private val peersActuales: () -> List<Peer>,
    /** Abre el selector del sistema para añadir archivos a la bandeja. */
    private val alElegir: () -> Unit = {}
) {

    val vista: View

    private val marcadosArchivos = linkedSetOf<String>()
    private val marcadosEquipos = linkedSetOf<String>()

    /**
     * Equipos que se estan mostrando ahora mismo, por fingerprint.
     * Es la fuente unica para el boton Enviar: asi vale tanto para un equipo
     * conectado que aun no estaba guardado como destino, como para uno
     * desconectado que ya lo estaba. (Antes se leia solo de Bandeja.destinos y
     * un equipo conectado recien marcado se quedaba fuera del envio.)
     */
    private val mostrados = linkedMapOf<String, Destino>()

    private val contenedorArchivos = LinearLayout(context)
    private val contenedorEquipos = LinearLayout(context)
    private val contenedorTareas = LinearLayout(context)
    private val tituloEnviar = TextView(context)

    init {
        vista = construir()
    }

    // ------------------------------------------------------------------ pintado

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun redondo(color: Int, radio: Int = 14): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radio).toFloat()
        setColor(color)
    }

    private fun construir(): View {
        val raiz = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(12))
            background = redondo(0xFF11151C.toInt(), 18)
        }

        // ---- cabecera
        val cab = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cab.addView(TextView(context).apply {
            text = "TransferBubble"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        cab.addView(boton("Cerrar", 0xFF37474F.toInt()) { alCerrar() })
        raiz.addView(cab)

        // ---- 1) archivos
        val filaArch = filaSeccion("1) Archivos en la bandeja", "Añadir", 0xFF1B5E20.toInt()) {
            alElegir()
        }
        // El boton Vaciar borra tambien las copias del disco (libera espacio).
        filaArch.addView(boton("Vaciar", 0xFF4E342E.toInt()) {
            Bandeja.vaciar()
            refrescar()
        })
        raiz.addView(filaArch)
        val scrollA = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(contenedorArchivos)
        }
        contenedorArchivos.orientation = LinearLayout.VERTICAL
        raiz.addView(scrollA)

        // ---- 2) equipos
        raiz.addView(seccion("2) Enviar a estos equipos"))
        val scrollE = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(contenedorEquipos)
        }
        contenedorEquipos.orientation = LinearLayout.VERTICAL
        raiz.addView(scrollE)

        // ---- 3) cola y boton
        //
        // BUG CORREGIDO: la cola de envios no tenia limite de altura, asi que
        // al acumularse tareas fallidas empujaba los archivos fuera de la
        // pantalla y ya no se podian marcar. Ahora la cola tiene una altura
        // FIJA pequena, solo muestra los ultimos 3 envios y tiene un boton
        // "Limpiar".
        raiz.addView(filaSeccion("Cola de envios (ultimos 3)", "Limpiar", 0xFF5D4037.toInt()) {
            Bandeja.limpiarTareas()
            refrescar()
        })
        val scrollT = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96)
            )
            addView(contenedorTareas)
        }
        contenedorTareas.orientation = LinearLayout.VERTICAL
        raiz.addView(scrollT)

        tituloEnviar.apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            background = redondo(0xFF1565C0.toInt(), 16)
            setPadding(0, dp(14), 0, dp(14))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                val archivos = marcadosArchivos.toList()
                // Se leen de los equipos MOSTRADOS (conectados o no), no solo de
                // los destinos guardados: asi un equipo recien marcado en la
                // lista tambien recibe el envio.
                val equipos = marcadosEquipos.mapNotNull { mostrados[it] }
                if (archivos.isEmpty() || equipos.isEmpty()) {
                    aviso("Marca al menos un archivo y un equipo")
                    return@setOnClickListener
                }
                // Se apuntan como destinos para no perderlos de vista luego.
                equipos.forEach { d -> Bandeja.recordarDestino(d) }
                val n = alEnviar(archivos, equipos)
                aviso("Programado: $n envio(s). Lo que no este conectado queda en espera.")
            }
        }
        raiz.addView(tituloEnviar)

        return raiz
    }

    /**
     * Fila de seccion con un boton a la derecha (para "Añadir" y "Limpiar").
     * Antes solo habia texto, y el usuario no tenia forma de añadir archivos
     * directamente desde la burbuja.
     */
    private fun filaSeccion(t: String, etiquetaBoton: String, color: Int, alPulsar: () -> Unit): View {
        val fila = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(6))
        }
        fila.addView(TextView(context).apply {
            text = t
            setTextColor(0xFF9FB3C8.toInt())
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        fila.addView(boton(etiquetaBoton, color, alPulsar))
        return fila
    }

    private fun seccion(t: String) = TextView(context).apply {
        text = t
        setTextColor(0xFF9FB3C8.toInt())
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(6))
    }

    private fun boton(t: String, color: Int, alPulsar: () -> Unit) = TextView(context).apply {
        text = t
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 13f
        gravity = Gravity.CENTER
        background = redondo(color, 12)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { alPulsar() }
    }

    private fun aviso(t: String) {
        tituloEnviar.text = t
    }

    // ----------------------------------------------------------------- refresco

    /** Repinta bandeja, equipos y cola con los datos de ahora mismo. */
    fun refrescar(aliasPropio: String, fingerprintPropio: String) {
        pintarArchivos()
        pintarEquipos()
        pintarTareas()
        tituloEnviar.text = "Enviar lo marcado"
    }

    private fun pintarArchivos() {
        contenedorArchivos.removeAllViews()
        val items = Bandeja.items
        if (items.isEmpty()) {
            contenedorArchivos.addView(vacio(
                "La bandeja esta vacia.\nUsa Compartir -> TransferBubble en cualquier app " +
                "para ir guardando archivos aqui."
            ))
            return
        }
        // Info de espacio: transparencia sobre el coste real de guardar copias.
        val total = items.sumOf { it.size }
        contenedorArchivos.addView(vacio(
            "${items.size} archivo(s) · ${total / (1024 * 1024)} MB"
        ))
        items.forEach { item ->
            val fila = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val check = CheckBox(context).apply {
                isChecked = marcadosArchivos.contains(item.id)
                setOnCheckedChangeListener { _, marcado ->
                    if (marcado) marcadosArchivos.add(item.id) else marcadosArchivos.remove(item.id)
                }
            }
            fila.addView(check)
            val texto = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texto.addView(TextView(context).apply {
                text = item.fileName
                setTextColor(0xFFE8F0F8.toInt())
                textSize = 14f
            })
            texto.addView(TextView(context).apply {
                text = "${item.size / 1024} KB"
                setTextColor(0xFF7E93A8.toInt())
                textSize = 11f
            })
            fila.addView(texto, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fila.addView(boton("Quitar", 0xFF5D4037.toInt()) {
                marcadosArchivos.remove(item.id)
                Bandeja.quitar(item.id)
            })
            contenedorArchivos.addView(fila)
        }
    }

    private fun pintarEquipos() {
        contenedorEquipos.removeAllViews()
        mostrados.clear()
        val peers = peersActuales()
        val vistos = peers.associateBy { it.fingerprint }

        // Primero los conectados ahora mismo.
        peers.forEach { peer ->
            val d = Destino(peer.fingerprint, peer.alias, peer.deviceModel)
            mostrados[peer.fingerprint] = d
            contenedorEquipos.addView(filaEquipo(
                nombre = peer.displayName,
                detalle = "${peer.ip} · conectado",
                destino = d,
                conectado = true,
                marcarSiPrimeraVez = true
            ))
        }

        // Despues los que ya no estan: siguen en la lista para poder programarlos.
        Bandeja.destinos.filter { !vistos.containsKey(it.fingerprint) }.forEach { d ->
            mostrados[d.fingerprint] = d
            contenedorEquipos.addView(filaEquipo(
                nombre = d.displayName,
                detalle = "desconectado · quedara en espera",
                destino = d,
                conectado = false,
                marcarSiPrimeraVez = false
            ))
        }

        if (contenedorEquipos.childCount == 0) {
            contenedorEquipos.addView(vacio("No hay equipos todavia. Enciende la app en el otro movil."))
        }
    }

    private fun filaEquipo(
        nombre: String,
        detalle: String,
        destino: Destino,
        conectado: Boolean,
        marcarSiPrimeraVez: Boolean
    ): View {
        // Un equipo conectado se marca solo la primera vez (comodidad).
        if (marcarSiPrimeraVez && marcadosEquipos.isEmpty()) marcadosEquipos.add(destino.fingerprint)

        val fila = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        fila.addView(CheckBox(context).apply {
            isChecked = marcadosEquipos.contains(destino.fingerprint)
            setOnCheckedChangeListener { _, marcado ->
                if (marcado) marcadosEquipos.add(destino.fingerprint)
                else marcadosEquipos.remove(destino.fingerprint)
            }
        })
        val texto = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texto.addView(TextView(context).apply {
            text = nombre
            setTextColor(if (conectado) 0xFFE8F0F8.toInt() else 0xFF8E9BA8.toInt())
            textSize = 14f
        })
        texto.addView(TextView(context).apply {
            text = detalle
            setTextColor(0xFF7E93A8.toInt())
            textSize = 11f
        })
        fila.addView(texto, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return fila
    }

    private fun pintarTareas() {
        contenedorTareas.removeAllViews()
        val tareas = Bandeja.tareas
        if (tareas.isEmpty()) {
            contenedorTareas.addView(vacio("Sin envios pendientes."))
            return
        }
        // Solo los ULTIMOS 3: si se acumulan fallos, no deben copar el panel
        // (era el bug de "no dejaba ver los archivos para envio").
        val recientes = tareas.takeLast(3).reversed()
        if (tareas.size > 3) {
            contenedorTareas.addView(vacio("(${tareas.size - 3} envio(s) mas arriba; pulsa Limpiar)"))
        }
        recientes.forEach { t ->
            val color = when (t.estado) {
                EstadoTarea.ENVIADO -> 0xFF2E7D32.toInt()
                EstadoTarea.FALLO -> 0xFFC62828.toInt()
                EstadoTarea.ENVIANDO -> 0xFF1565C0.toInt()
                EstadoTarea.ESPERANDO_EQUIPO -> 0xFF6D4C41.toInt()
            }
            val fila = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = redondo(0xFF1B2530.toInt(), 10)
            }
            fila.addView(TextView(context).apply {
                text = "${t.destinoNombre} · ${t.itemIds.size} archivo(s)"
                setTextColor(0xFFE8F0F8.toInt())
                textSize = 13f
            })
            fila.addView(TextView(context).apply {
                text = when (t.estado) {
                    EstadoTarea.ESPERANDO_EQUIPO -> "⏳ ${t.detalle}"
                    EstadoTarea.ENVIANDO -> "⬆ Enviando… ${t.progreso} %"
                    EstadoTarea.ENVIADO -> "✔ ${t.detalle}"
                    EstadoTarea.FALLO -> "✖ ${t.detalle}"
                }
                setTextColor(color)
                textSize = 12f
            })
            contenedorTareas.addView(fila, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }
    }

    private fun vacio(t: String) = TextView(context).apply {
        text = t
        setTextColor(0xFF8E9BA8.toInt())
        textSize = 12f
        setPadding(0, dp(6), 0, dp(6))
    }
}