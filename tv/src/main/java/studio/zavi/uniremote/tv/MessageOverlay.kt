package studio.zavi.uniremote.tv

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Draws a message on top of whatever is playing. Needs "Display over other apps";
 * without it, falls back to a system toast (shorter, but still on screen).
 */
class MessageOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var current: View? = null
    private val hideTask = Runnable { hide() }

    fun canDraw(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(text: String, seconds: Int, style: String) {
        if (!canDraw()) {
            // Toasts last ~3.5 s; repeat to roughly match the requested time.
            val repeats = (seconds / 3).coerceIn(1, 4)
            for (i in 0 until repeats) handler.postDelayed({ Toast.makeText(context, text, Toast.LENGTH_LONG).show() }, i * 3500L)
            return
        }
        hide()
        val full = style == "full"
        val view = if (full) fullView(text) else bannerView(text)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = if (full) 0 else dp(36)
        }
        view.alpha = 0f
        runCatching { wm.addView(view, params) }.onFailure { return }
        current = view
        view.animate().alpha(1f).setDuration(250).start()
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, seconds * 1000L)
    }

    fun hide() {
        handler.removeCallbacks(hideTask)
        val v = current ?: return
        current = null
        v.animate().alpha(0f).setDuration(200).withEndAction { runCatching { wm.removeView(v) } }.start()
    }

    private fun bannerView(text: String): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                colors = intArrayOf(Color.argb(235, 30, 34, 52), Color.argb(235, 14, 16, 26))
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
            elevation = dp(12).toFloat()
        }
        card.addView(TextView(context).apply {
            setText("Message from phone")
            setTextColor(Color.argb(170, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        card.addView(TextView(context).apply {
            setText(text)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        })
        return FrameLayout(context).apply {
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
    }

    private fun fullView(text: String): View = FrameLayout(context).apply {
        setBackgroundColor(Color.argb(215, 0, 0, 0))
        addView(
            TextView(context).apply {
                setText(text)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(60), dp(40), dp(60), dp(40))
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
