package studio.zavi.uniremote.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address

/** The TV app's only screen: status, IP address, and the "display over apps" permission. */
class TvMainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var allow: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageService.start(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(64), dp(40), dp(64), dp(40))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 7, 13), Color.rgb(22, 18, 48)),
            )
        }
        root.addView(text("Universal Remote TV", 40f, bold = true))
        root.addView(text("Messages from your phone will pop up on this screen.", 20f, alpha = 190).apply {
            setPadding(0, dp(8), 0, dp(28))
        })
        status = text("", 18f, alpha = 220)
        root.addView(status)
        root.addView(text("TV address: ${ipAddress() ?: "not on Wi-Fi"}", 18f, alpha = 160).apply {
            setPadding(0, dp(6), 0, dp(28))
        })

        allow = button("Allow messages over other apps") { openOverlaySettings() }
        root.addView(allow)
        root.addView(button("Show a test message") {
            MessageService.start(
                this,
                Intent(this, MessageService::class.java)
                    .putExtra(MessageService.EXTRA_TEXT, "Hello from Universal Remote 👋")
                    .putExtra(MessageService.EXTRA_SECONDS, 5),
            )
        })
        root.addView(text("You can press Home now — it keeps running in the background.", 16f, alpha = 140).apply {
            setPadding(0, dp(28), 0, 0)
        })
        setContentView(root)
        allow.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val ok = MessageOverlay(this).canDraw()
        status.text = if (ok) {
            "✓ Ready — messages will appear over anything you're watching"
        } else {
            "⚠ Allow \"display over other apps\" for big pop-ups (small ones work without it)"
        }
        status.setTextColor(if (ok) Color.rgb(48, 209, 88) else Color.rgb(255, 196, 0))
        allow.visibility = if (ok) Button.GONE else Button.VISIBLE
    }

    private fun openOverlaySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        status.text = "This TV hides that setting. Small pop-ups will still work."
    }

    private fun ipAddress(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
    }

    private fun text(s: String, sp: Float, bold: Boolean = false, alpha: Int = 255) = TextView(this).apply {
        text = s
        setTextColor(Color.argb(alpha, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(460), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/** Starts the message service when the TV boots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { MessageService.start(context) }
    }
}
