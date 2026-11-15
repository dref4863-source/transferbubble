package com.dref.transferbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import com.dref.transferbubble.net.FileToSend
import com.dref.transferbubble.net.PeerDiscovery

class MainActivity : ComponentActivity() {

    private lateinit var discovery: PeerDiscovery

    /**
     * Permisos de lectura de medios.
     *
     * BUG CORREGIDO (causa principal de que no se enviara nada): la app
     * declaraba READ_MEDIA_IMAGES/VIDEO y READ_EXTERNAL_STORAGE en el
     * manifiesto, pero NUNCA los solicitaba en tiempo de ejecucion.
     * Sin ese permiso, contentResolver.query() sobre un content:// de la
     * Galeria devuelve vacio: el nombre quedaba como "archivo", el tamano en 0
     * y la lectura del contenido fallaba. Ahora se piden al arrancar.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        android.util.Log.i("TB-Main", "Permisos concedidos: $result")
    }

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

        requestMediaPermissions()

        // FASE 4: la bandeja se inicializa aqui para que los archivos guardados
        // aparezcan en cuanto se abre la app, aunque la burbuja aun no este
        // activada (si se inicializara solo con la burbuja, la lista se veria
        // vacia hasta activarla).
        Bandeja.init(this)

        discovery = PeerDiscovery(this)

        // Fase 2: el receptor vive en un servicio en primer plano, asi que
        // se sigue recibiendo aunque la app se cierre o pase a segundo plano.
        ReceiverService.start(this)

        handleShareIntent(intent)

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        activity = this,
                        discovery = discovery
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

    /** Pide en tiempo de ejecucion los permisos que el manifiesto declara. */
    private fun requestMediaPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            // Android 12 y anteriores
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            android.util.Log.i("TB-Main", "Solicitando permisos: $missing")
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            android.util.Log.i("TB-Main", "Permisos de medios ya concedidos")
        }
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

            // FASE 4: ademas de quedar listos para enviar desde la app, se
            // guardan en la BANDEJA. Asi se pueden ir acumulando varios
            // archivos a lo largo del dia y enviarlos despues desde la burbuja.
            newFiles.forEach { f ->
                Bandeja.agregar(this, f.uri, f.fileName, f.size, f.mimeType)
            }
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