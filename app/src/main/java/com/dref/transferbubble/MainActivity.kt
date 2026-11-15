package com.dref.transferbubble

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import com.dref.transferbubble.net.FileToSend
import com.dref.transferbubble.net.HttpReceiver
import com.dref.transferbubble.net.PeerDiscovery

class MainActivity : ComponentActivity() {

    private lateinit var discovery: PeerDiscovery
    lateinit var receiver: HttpReceiver
        private set

    /**
     * Archivos que llegan por el menu Compartir.
     *
     * Es un estado observable de Compose (mutableStateOf) a proposito: con
     * launchMode=singleTask la Activity se reutiliza y onNewIntent se dispara
     * con la app ya abierta. Una lista normal NO provocaria recomposicion y el
     * usuario tocaba el equipo sin que se enviara nada (bug observado).
     */
    val sharedFiles = mutableStateOf<List<FileToSend>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        discovery = PeerDiscovery(this)
        receiver = HttpReceiver(this).apply { start() }

        handleShareIntent(intent)

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        activity = this,
                        discovery = discovery,
                        receiver = receiver
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /** Lee ACTION_SEND / ACTION_SEND_MULTIPLE y resuelve nombre, tamano y tipo. */
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val newFiles: List<FileToSend> = when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.let { listOf(resolveFile(it, intent.type)) } ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.map { resolveFile(it, intent.type) } ?: emptyList()
            }
            else -> emptyList()
        }
        if (newFiles.isNotEmpty()) {
            android.util.Log.i("TB-Main", "Compartir recibido: ${newFiles.size} archivo(s)")
            newFiles.forEach { android.util.Log.i("TB-Main", "  ${it.fileName} (${it.size} bytes) ${it.uri}") }
            sharedFiles.value = newFiles
        }
    }

    private fun resolveFile(uri: Uri, mime: String?): FileToSend {
        var name = "archivo"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndex(OpenableColumns.SIZE)
                if (iName >= 0) name = c.getString(iName) ?: name
                if (iSize >= 0 && !c.isNull(iSize)) size = c.getLong(iSize)
            }
        }
        // Si el proveedor no informa del tamano, se pregunta al descriptor.
        if (size <= 0) {
            size = try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            } catch (_: Exception) { 0L }
        }
        val type = mime ?: contentResolver.getType(uri) ?: "application/octet-stream"
        android.util.Log.i("TB-Main", "Resuelto: name=$name size=$size type=$type")
        return FileToSend(uri.toString(), name, size, type)
    }
}