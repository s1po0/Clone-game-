package app.mangascribe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.mangascribe.ui.MangaScribeApp
import app.mangascribe.ui.theme.MangaScribeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Files handed over by the share sheet, consumed once by the compose tree. */
    private var sharedUris by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedUris = readSharedImages(intent)
        setContent {
            MangaScribeTheme {
                val uriState = rememberUpdatedState(sharedUris)
                MangaScribeApp(sharedUris = uriState.value, onSharedConsumed = { sharedUris = emptyList() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = readSharedImages(intent)
        if (incoming.isNotEmpty()) sharedUris = incoming
    }

    /**
     * Share sheet → single or multiple images. URIs are persisted only when the sender granted
     * permission; otherwise they are copied into the app's own storage during import, which is
     * why the copy happens immediately rather than lazily.
     */
    private fun readSharedImages(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.filterNotNull().orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        return uris.filter { "image" in (contentResolver.getType(it) ?: "") }
    }
}
