package com.dref.transferbubble

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.dref.transferbubble.net.FileToSend
import com.dref.transferbubble.net.HttpReceiver
import com.dref.transferbubble.net.PeerDiscovery

class MainActivity : ComponentActivity() {

    private lateinit var discovery: PeerDiscovery
    lateinit var receiver: HttpReceiver
        private set

    /** Archivos que llegaron por el menu Compartir de otra app. */
    var incomingFiles: List<FileToSend> = emptyList()
        private set

    var incomingText: String? = null
        private set

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
                        receiver = receiver,
                        sharedFiles = incomingFiles
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

    /** Lee ACTION_SEND / ACTION_SEND_MULTIPLE y resuelve nombre y tamano reales. */
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: return
                incomingFiles = listOf(resolveFile(uri, intent.type))
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: return
                incomingFiles = uris.map { resolveFile(it, intent.type) }
            }
            else -> return
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
        val type = mime ?: contentResolver.getType(uri) ?: "application/octet-stream"
        return FileToSend(uri.toString(), name, size, type)
    }
}