package com.aegis.adblock

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * NEW FILE - does not modify MainActivity.kt, BrowserActivity.kt or any other
 * original file. This is purely an additive, aesthetic landing screen that
 * sits in front of the existing app and launches the existing, untouched
 * activities. All real logic (VPN toggle, blocklist update, browser) still
 * lives exactly where it always did.
 */
class HomeActivity : android.app.Activity() {

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var countsText: TextView

    private val statsTick = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@HomeActivity, R.drawable.aegis_bg_home)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(28), px(56), px(28), px(40))
        }

        // --- Title ---
        val title = TextView(this).apply {
            text = "Aegis AdBlock"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Internet lebih bersih, tanpa iklan"
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.aegis_text_secondary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, px(6), 0, px(28))
        }

        // --- Status chip (dot + live text) ---
        dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(10), px(10)).apply {
                rightMargin = px(8)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = ContextCompat.getDrawable(this@HomeActivity, R.drawable.aegis_dot)
        }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18), px(10), px(18), px(10))
            background = ContextCompat.getDrawable(this@HomeActivity, R.drawable.aegis_chip_bg)
            addView(dot)
            addView(statusText)
        }

        countsText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.aegis_text_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, px(12), 0, px(36))
        }

        // --- Big aesthetic action buttons ---
        val btnProtection = makeActionButton(
            label = "Buka Panel Proteksi",
            sub = "Aktifkan / matikan DNS filter & lihat statistik",
            bgRes = R.drawable.aegis_btn_primary
        ) { startActivity(Intent(this, MainActivity::class.java)) }

        val btnBrowser = makeActionButton(
            label = "Browser Anti-Iklan",
            sub = "Jelajah & nonton YouTube tanpa gangguan iklan",
            bgRes = R.drawable.aegis_btn_secondary
        ) { startActivity(Intent(this, BrowserActivity::class.java)) }

        val btnQuickToggle = makeActionButton(
            label = if (AdBlockVpnService.running) "Matikan Proteksi Cepat" else "Aktifkan Proteksi Cepat",
            sub = "Pintasan tanpa membuka panel penuh",
            bgRes = R.drawable.aegis_btn_tertiary,
            compact = true
        ) {
            // Delegates to the exact same, untouched entry points MainActivity uses.
            if (AdBlockVpnService.running) {
                startService(Intent(this, AdBlockVpnService::class.java).setAction("STOP"))
            } else {
                val i = android.net.VpnService.prepare(this)
                if (i != null) startActivityForResult(i, 1001)
                else startService(Intent(this, AdBlockVpnService::class.java))
            }
            uiHandler.postDelayed({ refreshStatus() }, 400)
        }

        content.addView(title)
        content.addView(subtitle)
        content.addView(chipRow)
        content.addView(countsText)
        content.addView(spacer(px(4)))
        content.addView(btnProtection)
        content.addView(spacer(px(16)))
        content.addView(btnBrowser)
        content.addView(spacer(px(16)))
        content.addView(btnQuickToggle)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        animateEntrance(listOf(title, subtitle, chipRow, countsText, btnProtection, btnBrowser, btnQuickToggle))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            startService(Intent(this, AdBlockVpnService::class.java))
        }
        uiHandler.postDelayed({ refreshStatus() }, 400)
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(statsTick)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(statsTick)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshStatus() {
        val on = AdBlockVpnService.running
        statusText.text = if (on) "Proteksi AKTIF" else "Proteksi tidak aktif"
        (dot.background as? GradientDrawable)?.setColor(
            ContextCompat.getColor(this, if (on) R.color.aegis_chip_on else R.color.aegis_chip_off)
        )
        countsText.text = "Domain di blocklist: ${Blocklist.size()}  •  Query: ${AdBlockVpnService.totalCount.get()}  •  Diblokir: ${AdBlockVpnService.blockedCount.get()}"
    }

    /**
     * Builds one "card button": gradient/glassy rounded background (ripple
     * baked into the drawable), a bold title + small subtitle, plus a
     * press-in scale + elevation animation on top of the native ripple so
     * the press feels tactile.
     */
    private fun makeActionButton(
        label: String,
        sub: String,
        bgRes: Int,
        compact: Boolean = false,
        onClick: () -> Unit
    ): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(this@HomeActivity, bgRes)
            setPadding(px(24), px(if (compact) 16 else 22), px(24), px(if (compact) 16 else 22))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            elevation = px(6).toFloat()
        }

        val titleView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (compact) 16f else 18f
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val subView = TextView(this).apply {
            text = sub
            setTextColor(Color.parseColor("#E6FFFFFF"))
            textSize = 12.5f
            setPadding(0, px(4), 0, 0)
        }

        container.addView(titleView)
        container.addView(subView)

        container.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).translationZ(-px(2).toFloat())
                        .setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).translationZ(0f)
                        .setDuration(140).setInterpolator(DecelerateInterpolator()).start()
                }
            }
            false // let the click listener / ripple still receive the event
        }
        container.setOnClickListener { onClick() }

        return container
    }

    private fun spacer(heightPx: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx)
    }

    /** Simple staggered fade + slide-up entrance so the screen feels alive on open. */
    private fun animateEntrance(views: List<View>) {
        val dp = resources.displayMetrics.density
        views.forEachIndexed { index, v ->
            v.alpha = 0f
            v.translationY = 24 * dp
            val fade = ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f)
            val slide = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 24 * dp, 0f)
            AnimatorSet().apply {
                playTogether(fade, slide)
                duration = 320
                startDelay = (index * 60).toLong()
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }
}
