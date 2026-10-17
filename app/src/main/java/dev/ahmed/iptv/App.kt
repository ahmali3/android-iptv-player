package dev.ahmed.iptv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dev.ahmed.iptv.data.Db
import dev.ahmed.iptv.data.Playlist
import dev.ahmed.iptv.data.Repo
import dev.ahmed.iptv.player.PlayerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class App : Application(), ImageLoaderFactory {

    lateinit var db: Db
        private set
    lateinit var repo: Repo
        private set
    lateinit var player: PlayerHolder
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    /** Id of the playlist being browsed (0 = none). */
    var activePlaylistId by mutableLongStateOf(0L)
        private set

    /** Bumped whenever stored data changes, so screens re-query. */
    var dataVersion by mutableIntStateOf(0)
        private set

    /** Progress or error text from the current refresh, shown in a banner. */
    var status by mutableStateOf<String?>(null)
    var refreshing by mutableStateOf(false)
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Db(this)
        repo = Repo(this, db)
        player = PlayerHolder(this, repo)
        activePlaylistId = prefs().getLong(KEY_ACTIVE, 0L)
    }

    private fun prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun changed() {
        dataVersion++
    }

    fun setActive(id: Long) {
        activePlaylistId = id
        prefs().edit().putLong(KEY_ACTIVE, id).apply()
        changed()
    }

    suspend fun activePlaylist(): Playlist? = withContext(Dispatchers.IO) {
        val all = db.playlists()
        all.firstOrNull { it.id == activePlaylistId } ?: all.firstOrNull()?.also {
            withContext(Dispatchers.Main) { setActive(it.id) }
        }
    }

    /** Refreshes on app start if the active playlist is more than 12 hours old. */
    fun refreshIfStale() {
        scope.launch {
            val p = activePlaylist() ?: return@launch
            if (System.currentTimeMillis() - p.lastRefresh > 12 * 60 * 60 * 1000L) refresh(p.id)
        }
    }

    /** Reloads channels, then the guide, for one playlist. Safe to call repeatedly. */
    fun refresh(playlistId: Long) {
        scope.launch {
            if (!refreshLock.tryLock()) return@launch
            try {
                setRefreshing(true)
                val p = db.playlists().firstOrNull { it.id == playlistId } ?: return@launch
                val updated = repo.refreshChannels(p) { postNow(it) }
                ui { changed() }
                try {
                    val n = repo.refreshGuide(updated) { postNow(it) }
                    post(if (n > 0) null else "No guide data for this playlist")
                } catch (e: Exception) {
                    post("Channels updated, but the guide failed: ${e.message}")
                }
                ui { changed() }
            } catch (e: Exception) {
                post(e.message ?: "Refresh failed")
            } finally {
                setRefreshing(false)
                refreshLock.unlock()
            }
        }
    }

    private suspend fun setRefreshing(v: Boolean) = ui { refreshing = v }
    private suspend fun post(msg: String?) = ui { status = msg }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Non-suspending status update for progress callbacks running on a background thread. */
    private fun postNow(msg: String?) {
        main.post { status = msg }
    }
    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(64L * 1024 * 1024).build() }
        .okHttpClient { repo.http }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()

    companion object {
        lateinit var instance: App
            private set
        private const val KEY_ACTIVE = "active_playlist"
    }
}
