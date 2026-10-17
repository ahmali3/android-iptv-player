package dev.ahmed.iptv.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Google Cast: finds Chromecasts / Android TVs on the Wi-Fi, connects, and plays channels on them.
 * Each channel is first sent to the TV directly; if the TV can't load it, it is retried through
 * the phone's [Relay].
 */
class CastManager(private val context: Context, private val http: OkHttpClient) {

    interface Listener {
        fun onCastStarted()
        fun onCastEnded()
    }

    data class Device(val id: String, val name: String, val description: String)

    var listener: Listener? = null

    /** False when Google Play services / Cast aren't available on this phone. */
    var available by mutableStateOf(false)
        private set
    var deviceName by mutableStateOf<String?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set
    val devices = mutableStateListOf<Device>()

    var remotePlaying by mutableStateOf(false)
        private set
    var remoteBuffering by mutableStateOf(false)
        private set
    var remoteError by mutableStateOf<String?>(null)
        private set
    /** True while the current channel goes through the phone. */
    var viaPhone by mutableStateOf(false)
        private set

    val casting: Boolean get() = deviceName != null

    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()
    private var relay: Relay? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private data class Load(val url: String, val title: String, val subtitle: String?, val logo: String, val id: Int)
    private var current: Load? = null
    private var loadSeq = 0
    private var triedRelay = false
    private var sawPlayback = false

    /** Call once on the main thread. */
    fun init() {
        val ctx = try {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(context)
        } catch (e: Throwable) {
            Log.w(TAG, "Cast unavailable: ${e.message}")
            null
        } ?: return
        castContext = ctx
        router = MediaRouter.getInstance(context)
        available = true
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { attach(it) }
    }

    // ---------- discovery ----------

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
    }

    fun startScan() {
        val r = router ?: return
        r.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refreshRoutes()
    }

    fun stopScan() {
        router?.removeCallback(routerCallback)
    }

    private fun refreshRoutes() {
        val r = router ?: return
        val list = r.routes
            .filter { !it.isDefault && !it.isBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map { Device(it.id, it.name, it.description.orEmpty()) }
        devices.clear()
        devices.addAll(list)
    }

    fun connect(device: Device) {
        val r = router ?: return
        val route = r.routes.firstOrNull { it.id == device.id } ?: return
        connecting = true
        r.selectRoute(route)
    }

    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    // ---------- sessions ----------

    private var session: CastSession? = null
    private val client: RemoteMediaClient? get() = session?.remoteMediaClient

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) { connecting = true }
        override fun onSessionStarted(s: CastSession, sessionId: String) = attach(s)
        override fun onSessionStartFailed(s: CastSession, error: Int) { connecting = false; remoteError = "Couldn't connect to the TV" }
        override fun onSessionEnding(s: CastSession) = Unit
        override fun onSessionEnded(s: CastSession, error: Int) = detach()
        override fun onSessionResuming(s: CastSession, sessionId: String) { connecting = true }
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = attach(s)
        override fun onSessionResumeFailed(s: CastSession, error: Int) { connecting = false }
        override fun onSessionSuspended(s: CastSession, reason: Int) = Unit
    }

    private fun attach(s: CastSession) {
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        session = s
        connecting = false
        remoteError = null
        deviceName = s.castDevice?.friendlyName ?: "TV"
        s.remoteMediaClient?.registerCallback(mediaCallback)
        listener?.onCastStarted()
    }

    private fun detach() {
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        session = null
        connecting = false
        deviceName = null
        remotePlaying = false
        remoteBuffering = false
        current = null
        stopRelay()
        listener?.onCastEnded()
    }

    // ---------- playback ----------

    fun load(url: String, title: String, subtitle: String?, logo: String) {
        handler.removeCallbacksAndMessages(null)
        triedRelay = false
        sawPlayback = false
        remoteError = null
        val l = Load(url, title, subtitle, logo, ++loadSeq)
        current = l
        send(l, viaRelay = false)
    }

    private fun send(l: Load, viaRelay: Boolean) {
        val c = client ?: return
        viaPhone = viaRelay
        val castUrl = CastUrls.castUrl(l.url)
        val target = if (viaRelay) relayFor()?.wrap(castUrl) ?: return else castUrl
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, l.title)
            l.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            if (l.logo.startsWith("http")) addImage(WebImage(Uri.parse(l.logo)))
        }
        val info = MediaInfo.Builder(target)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(CastUrls.mimeType(castUrl))
            .setMetadata(meta)
            .build()
        remoteBuffering = true
        Log.i(TAG, "cast load (relay=$viaRelay): $castUrl")
        c.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
            .setResultCallback { r ->
                if (current?.id != l.id) return@setResultCallback
                if (!r.status.isSuccess) {
                    Log.w(TAG, "cast load failed: ${r.status}")
                    failed(l)
                }
            }
        // Some receivers never report an error for a stream they can't fetch; give them 20 s.
        handler.postDelayed({ if (current?.id == l.id && !sawPlayback) failed(l) }, 20_000)
    }

    private fun failed(l: Load) {
        handler.removeCallbacksAndMessages(null)
        if (!triedRelay && relayFor() != null) {
            triedRelay = true
            sawPlayback = false
            send(l, viaRelay = true)
        } else {
            remoteBuffering = false
            remotePlaying = false
            remoteError = "This channel won't play on ${deviceName ?: "the TV"}. Try another channel, or play it on the phone."
        }
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val c = client ?: return
            val state = c.playerState
            remotePlaying = state == MediaStatus.PLAYER_STATE_PLAYING
            remoteBuffering = state == MediaStatus.PLAYER_STATE_BUFFERING || state == MediaStatus.PLAYER_STATE_LOADING
            if (state == MediaStatus.PLAYER_STATE_PLAYING || state == MediaStatus.PLAYER_STATE_PAUSED) {
                sawPlayback = true
                remoteError = null
            }
            if (state == MediaStatus.PLAYER_STATE_IDLE && c.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                current?.let { failed(it) }
            }
        }
    }

    fun togglePause() {
        val c = client ?: return
        if (c.isPlaying) c.pause() else if (current != null && c.playerState == MediaStatus.PLAYER_STATE_IDLE) {
            current?.let { load(it.url, it.title, it.subtitle, it.logo) }
        } else c.play()
    }

    fun stopRemote() {
        handler.removeCallbacksAndMessages(null)
        current = null
        client?.stop()
    }

    // ---------- relay ----------

    private fun relayFor(): Relay? {
        relay?.let { if (it.running) return it }
        val ip = wifiAddress() ?: return null
        return try {
            Relay(http, ip).start().also {
                relay = it
                @Suppress("DEPRECATION")
                wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iptv:cast")
                    ?.apply { setReferenceCounted(false); acquire() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "relay failed to start: ${e.message}")
            null
        }
    }

    private fun stopRelay() {
        relay?.stop()
        relay = null
        runCatching { wifiLock?.release() }
        wifiLock = null
        viaPhone = false
    }

    /** The phone's IPv4 address on the local network, which the TV uses to reach the relay. */
    private fun wifiAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val fromActive = cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
        if (fromActive != null) return fromActive.hostAddress
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }

    companion object {
        private const val TAG = "IptvCast"
    }
}
