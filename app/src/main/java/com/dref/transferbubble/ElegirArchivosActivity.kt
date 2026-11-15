package com.dref.transferbubble

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Activity INVISIBLE que solo sirve para abrir el selector de archivos del
 * sistema desde la BURBUJA flotante.
 *
 * Por que existe: una ventana de superposicion (overlay) NO puede abrir un
 * selector de archivos por si sola, porque no tiene ciclo de vida ni recibe el
 * resultado. El usuario lo pidio como "no puedo compartir directamente desde
 * la burbuja": desde el panel pulsamos "Añadir" y esta activity abre el
 * selector, recoge lo elegido, lo mete en la BANDEJA y se cierra sola.
 *
 * Se declara con tema transparente para que no se vea nada: el usuario solo ve
 * aparecer el selector del sistema.
 *
 * DOS BUGS CORREGIDOS AQUI ("el boton Añadir no carga ningun archivo"):
 *
 *  1) EL MANIFIESTO TENIA `noHistory="true"`. Ese flag destruye la activity en
 *     cuanto deja de estar visible: al abrirse el selector del sistema, Android
 *     mataba esta activity, y al volver el resultado ya no habia nadie para
 *     recibirlo. El archivo se elegia y se perdia en silencio. (En el
 *     manifiesto ya se ha quitado el flag.)
 *
 *  2) SE USABA EL `startActivityForResult` ANTIGUO. En una activity sin
 *     historial el resultado podia llegar antes de que el sistema tuviera
 *     registrado el callback. Ahora se usa
 *     `ActivityResultContracts.StartActivityForResult`, que REGISTRA el
 *     resultado en `onCreate` ANTES de lanzar el selector: sin carreras.
 */
class ElegirArchivosActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TB-Elegir"
    }

    private val selectorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            data?.data?.let { uris.add(it) }
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            }
            Log.i(TAG, "Elegidos ${uris.size} archivo(s) desde la burbuja")
            uris.forEach { uri ->
                val (nombre, tamano) = datos(uri)
                Bandeja.agregar(
                    this, uri.toString(), nombre, tamano,
                    contentResolver.getType(uri) ?: "application/octet-stream"
                )
            }
        } else {
            Log.i(TAG, "Selector cerrado sin elegir nada")
        }
        // Se cierra sola: la burbuja sigue detras con la bandeja actualizada.
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Multiples a la vez: se pueden añadir varios archivos de una tacada.
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            Log.i(TAG, "Abriendo el selector para la burbuja")
            selectorLauncher.launch(i)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el selector: ${e.message}")
            finish()
        }
    }

    private fun datos(uri: Uri): Pair<String, Long> {
        var nombre = "archivo"
        var tamano = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iS = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iN >= 0) nombre = c.getString(iN) ?: nombre
                    if (iS >= 0 && !c.isNull(iS)) tamano = c.getLong(iS)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron leer los datos de $uri: ${e.message}")
        }
        return nombre to tamano
    }
}