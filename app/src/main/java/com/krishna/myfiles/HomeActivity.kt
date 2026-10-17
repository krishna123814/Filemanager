package com.krishna.myfiles

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Login ke baad ka pehla screen: do storage (AIC Disk, HF Bucket) + Transfers. */
class HomeActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var aicSub: TextView
    private lateinit var hfSub: TextView
    private lateinit var hfCard: LinearLayout
    private lateinit var clipTv: TextView

    /** null = HF theek (ya abhi pata nahi); String = HF band hai, ye sabab. */
    private var hfReason: String? = null

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Session.hasCreds()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (!::hfSub.isInitialized) return
        refreshClip()
        probeHf()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun buildUi() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "📂 MyFiles"
        title.textSize = 26f
        title.setTextColor(Color.WHITE)
        title.typeface = Typeface.DEFAULT_BOLD
        title.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val more = TextView(this)
        more.text = "⋮"
        more.textSize = 24f
        more.setTextColor(Color.WHITE)
        more.gravity = Gravity.CENTER
        more.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        more.setOnClickListener { showMenu() }
        top.addView(title)
        top.addView(more)
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val host = Session.baseUrl.toHttpUrlOrNull()?.host ?: ""

        aicSub = TextView(this)
        val aicCard = card(Store.AIC, aicSub) { open(Store.AIC) }
        aicSub.text = "VPS ki disk (/data) · $host"

        hfSub = TextView(this)
        hfCard = card(Store.HF, hfSub) {
            val r = hfReason
            if (r != null) {
                Toast.makeText(this, "❌ $r", Toast.LENGTH_LONG).show()
            } else {
                open(Store.HF)
            }
        }
        hfSub.text = "Check ho raha hai…"

        col.addView(aicCard)
        col.addView(hfCard)

        clipTv = TextView(this)
        clipTv.setTextColor(0xFFFFB300.toInt())
        clipTv.textSize = 13f
        clipTv.setPadding(dp(4), dp(8), dp(4), dp(8))
        col.addView(clipTv, LinearLayout.LayoutParams(MATCH, WRAP))

        val tr = Button(this)
        tr.text = "📊 Transfers"
        tr.setOnClickListener { startActivity(Intent(this, TransfersActivity::class.java)) }
        col.addView(tr, LinearLayout.LayoutParams(MATCH, WRAP))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFF121212.toInt())
        scroll.isFillViewport = true
        scroll.addView(col)
        setContentView(scroll)
    }

    private fun card(store: Store, sub: TextView, onClick: () -> Unit): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(20), dp(24), dp(20), dp(24))
        val bg = GradientDrawable()
        bg.setColor(0xFF1F1F1F.toInt())
        bg.cornerRadius = dp(16).toFloat()
        c.background = bg
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(14)
        c.layoutParams = lp

        val icon = TextView(this)
        icon.text = store.icon
        icon.textSize = 40f
        icon.setPadding(0, 0, dp(16), 0)

        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val name = TextView(this)
        name.text = store.label
        name.textSize = 20f
        name.setTextColor(Color.WHITE)
        name.typeface = Typeface.DEFAULT_BOLD
        sub.setTextColor(0xFF9E9E9E.toInt())
        sub.textSize = 13f
        txt.addView(name)
        txt.addView(sub)

        c.addView(icon)
        c.addView(txt)
        c.setOnClickListener { onClick() }
        return c
    }

    private fun open(store: Store) {
        startActivity(Intent(this, BrowserActivity::class.java).putExtra("store", store.id))
    }

    private fun refreshClip() {
        val c = Session.clip
        clipTv.text = if (c.isEmpty()) "" else
            "📋 ${c.size} item clipboard me (${if (Session.clipMove) "Move" else "Copy"}) — storage kholo, folder me jao aur 'Paste here' dabao"
    }

    private fun probeHf() {
        io.execute {
            val reason = Storages.hfDisabledReason()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                hfReason = reason
                if (reason == null) {
                    hfSub.text = "Hugging Face Storage Bucket"
                    hfCard.alpha = 1f
                } else {
                    hfSub.text = "❌ $reason"
                    hfCard.alpha = 0.5f
                }
            }
        }
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("🚪 Logout / server badlo")) { _, _ -> logout() }
            .show()
    }

    private fun logout() {
        stopService(Intent(this, PlaybackService::class.java))
        Session.clearPass(this)
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
