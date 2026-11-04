package studio.zavi.uniremote.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Runs in the background on the TV. Listens on [PORT] for one-line JSON messages from the
 * phone app ({"text": "...", "seconds": 8, "style": "banner"|"full"}) and shows them on screen.
 */
class MessageService : Service() {
    private var server: ServerSocket? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: MessageOverlay

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = MessageOverlay(this)
        startForeground(NOTIFICATION_ID, notification())
        startServer()
        advertise()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_TEXT)?.let { text ->
            overlay.show(text, intent.getIntExtra(EXTRA_SECONDS, 8), intent.getStringExtra(EXTRA_STYLE) ?: "banner")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.close() }
        nsdListener?.let { l -> runCatching { (getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) } }
        overlay.hide()
        super.onDestroy()
    }

    private fun startServer() {
        thread(name = "uniremote-tv-server", isDaemon = true) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                server = s
                while (!s.isClosed) {
                    val client = s.accept()
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (_: Exception) {
                // Socket closed on shutdown, or the port was busy.
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = 5000
                val line = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine() ?: return
                val msg = Json.parseToJsonElement(line) as? JsonObject ?: return
                val text = (msg["text"] as? JsonPrimitive)?.contentOrNull?.take(500) ?: return
                val seconds = ((msg["seconds"] as? JsonPrimitive)?.intOrNull ?: 8).coerceIn(2, 60)
                val style = (msg["style"] as? JsonPrimitive)?.contentOrNull ?: "banner"
                main.post { overlay.show(text, seconds, style) }
                socket.getOutputStream().write("ok\n".toByteArray())
                socket.getOutputStream().flush()
            }
        }
    }

    /** Lets the phone find this TV automatically. */
    private fun advertise() {
        val nsd = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Universal Remote TV"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        nsdListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages from phone", NotificationManager.IMPORTANCE_MIN))
            return Notification.Builder(this, CHANNEL)
                .setContentTitle("Universal Remote TV")
                .setContentText("Ready for messages from your phone")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("Universal Remote TV")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .build()
    }

    companion object {
        const val PORT = 7890
        const val SERVICE_TYPE = "_uniremote._tcp"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_STYLE = "style"
        private const val CHANNEL = "messages"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, intent: Intent = Intent(context, MessageService::class.java)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
