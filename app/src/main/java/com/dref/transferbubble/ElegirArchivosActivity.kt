package com.dref.transferbubble

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log

/**
 * Activity INVISIBLE que solo sirve para abrir el selector de archivos del
 * sistema desde la burbuja flotante.
 *
 * Por que existe: una ventana de superposicion (overlay) NO puede abrir un
 * selector de archivos por si sola, porque no tiene actividad ni recibe el
 * resultado. El usuario lo pidio como "no puedo compartir directamente desde
 * la burbuja": desde el panel pulsamos "Añadir" y esta activity abre el
 * selector, recoge lo elegido, lo mete en la BANDEJA y se cierra sola.
 *
 * Se declara con tema transparente para que no se vea nada: el usuario solo
 * ve aparecer el selector del sistema.
 */
class ElegirArchivosActivity : Activity() {

    companion object {
        private const val TAG = "TB-Elegir"
        private const val CODIGO = 4711
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Multiples a la vez: se pueden añadir varios archivos de una tacada.
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, CODIGO)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el selector: ${e.message}")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CODIGO && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.data?.let { uris.add(it) }
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            }
            Log.i(TAG, "Elegidos ${uris.size} archivo(s)")
            uris.forEach { uri ->
                val (nombre, tamano) = datos(uri)
                Bandeja.agregar(this, uri.toString(), nombre, tamano, contentResolver.getType(uri) ?: "application/octet-stream")
            }
        }
        finish()
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