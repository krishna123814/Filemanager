package com.krishna.myfiles

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class BrowserActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var store: Store

    private lateinit var adapter: FileAdapter
    private lateinit var gridLm: GridLayoutManager
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var emptyTv: TextView
    private lateinit var viewBtn: TextView
    private lateinit var header: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteInfo: TextView

    private var rootUrl = ""
    private var currentUrl = ""
    private val stack = ArrayList<String>()
    private var entries: List<Entry> = emptyList()
    private var loadToken = 0

    private var gridMode = false
    private var sortMode = 0
    private var videoThumbs = true

    // multi-select
    private var selecting = false
    private val selected = LinkedHashMap<String, Entry>()

    // transfer progress dialog
    private var progDlg: AlertDialog? = null
    private var progMsg: TextView? = null
    private var progBar: ProgressBar? = null

    private val sortNames = arrayOf("Naam (A→Z)", "Naam (Z→A)", "Naya pehle", "Purana pehle", "Bada pehle")

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) upload(uris)
        }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Session.hasCreds()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        store = Store.from(intent.getStringExtra("store"))
        rootUrl = Dav.withSlash(Session.rootFor(store))
        gridMode = Session.bool(this, "grid", false)
        sortMode = Session.int(this, "sort", 0).coerceIn(0, sortNames.size - 1)
        videoThumbs = Session.bool(this, "vthumbs", true)

        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selecting) exitSelect() else goBack()
            }
        })

        val saved = savedInstanceState?.getString("cur")
        val savedStack = savedInstanceState?.getStringArrayList("stack")
        if (savedStack != null) stack.addAll(savedStack)
        val start = intent.getStringExtra("start")?.takeIf { it.isNotEmpty() }
        load(saved ?: start ?: rootUrl, false)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (UpdateChecker.shouldAutoCheck(this)) {
            UpdateChecker.check(this) { info -> if (info != null) showUpdate(info) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::pasteBar.isInitialized) refreshPasteBar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("cur", currentUrl)
        outState.putStringArrayList("stack", ArrayList(stack))
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        closeProgress()
        io.shutdown()
    }

    // ───────────────────────── UI ─────────────────────────

    private fun iconBtn(t: String, size: Int = 48, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = t
        b.textSize = 22f
        b.setTextColor(Color.WHITE)
        b.gravity = Gravity.CENTER
        b.layoutParams = LinearLayout.LayoutParams(dp(size), dp(48))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun storeColor(): Int = if (store == Store.HF) 0xFFEF6C00.toInt() else 0xFF1565C0.toInt()

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF121212.toInt())

        header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(0xFF1F1F1F.toInt())
        header.setPadding(dp(4), 0, dp(4), 0)

        val back = iconBtn("←") { goBack() }

        // storage badge: dabane par doosre storage me jaa sakte ho (clipboard saath rehta hai)
        val badge = TextView(this)
        badge.text = store.icon + " " + store.short
        badge.textSize = 12f
        badge.setTextColor(Color.WHITE)
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.gravity = Gravity.CENTER
        badge.setPadding(dp(8), dp(3), dp(8), dp(3))
        val bbg = GradientDrawable()
        bbg.setColor(storeColor())
        bbg.cornerRadius = dp(10).toFloat()
        badge.background = bbg
        val blp = LinearLayout.LayoutParams(WRAP, WRAP)
        blp.leftMargin = dp(2)
        badge.layoutParams = blp
        badge.setOnClickListener { switchStorage() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        col.setPadding(dp(8), dp(8), dp(4), dp(8))

        titleTv = TextView(this)
        titleTv.setTextColor(Color.WHITE)
        titleTv.textSize = 17f
        titleTv.typeface = Typeface.DEFAULT_BOLD
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END

        subTv = TextView(this)
        subTv.setTextColor(0xFF9E9E9E.toInt())
        subTv.textSize = 12f
        subTv.maxLines = 1
        subTv.ellipsize = TextUtils.TruncateAt.END

        col.addView(titleTv)
        col.addView(subTv)

        viewBtn = iconBtn(if (gridMode) "☰" else "▦") { toggleView() }
        val more = iconBtn("⋮") { showMenu() }

        header.addView(back)
        header.addView(badge)
        header.addView(col)
        header.addView(viewBtn)
        header.addView(more)

        // selection bar (long-press se khulta hai, header ki jagah)
        selBar = LinearLayout(this)
        selBar.orientation = LinearLayout.HORIZONTAL
        selBar.gravity = Gravity.CENTER_VERTICAL
        selBar.setBackgroundColor(0xFF4A3800.toInt())
        selBar.setPadding(dp(4), 0, dp(4), 0)
        selBar.visibility = View.GONE
        selCount = TextView(this)
        selCount.setTextColor(Color.WHITE)
        selCount.textSize = 16f
        selCount.typeface = Typeface.DEFAULT_BOLD
        selCount.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        selBar.addView(iconBtn("✕", 44) { exitSelect() })
        selBar.addView(selCount)
        selBar.addView(iconBtn("☑", 44) { selectAll() })
        selBar.addView(iconBtn("📋", 44) { doClip(false) })
        selBar.addView(iconBtn("✂", 44) { doClip(true) })
        selBar.addView(iconBtn("🗑", 44) { confirmDelete(selected.values.toList()) })
        selBar.addView(iconBtn("⋮", 44) { selMore() })

        adapter = FileAdapter(this, { e -> onOpen(e) }, { e -> onLongPress(e) })
        applyThumbFlag()
        gridLm = GridLayoutManager(this, 1)
        rv = RecyclerView(this)
        rv.layoutManager = gridLm
        rv.adapter = adapter
        adapter.selected = selected.keys

        swipe = SwipeRefreshLayout(this)
        swipe.setColorSchemeColors(0xFFFFB300.toInt())
        swipe.setProgressBackgroundColorSchemeColor(0xFF2A2A2A.toInt())
        swipe.setOnRefreshListener { load(if (currentUrl.isEmpty()) rootUrl else currentUrl, false) }
        swipe.addView(rv, ViewGroup.LayoutParams(MATCH, MATCH))

        emptyTv = TextView(this)
        emptyTv.setTextColor(0xFF9E9E9E.toInt())
        emptyTv.gravity = Gravity.CENTER
        emptyTv.setPadding(dp(24), dp(24), dp(24), dp(24))
        emptyTv.visibility = View.GONE

        val body = FrameLayout(this)
        body.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        body.addView(swipe, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(emptyTv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // paste bar (clipboard me kuch ho to neeche hamesha dikhta hai)
        pasteBar = LinearLayout(this)
        pasteBar.orientation = LinearLayout.HORIZONTAL
        pasteBar.gravity = Gravity.CENTER_VERTICAL
        pasteBar.setBackgroundColor(0xFF2A2A2A.toInt())
        pasteBar.setPadding(dp(12), dp(6), dp(8), dp(6))
        pasteBar.visibility = View.GONE
        pasteInfo = TextView(this)
        pasteInfo.setTextColor(Color.WHITE)
        pasteInfo.textSize = 13f
        pasteInfo.maxLines = 2
        pasteInfo.ellipsize = TextUtils.TruncateAt.END
        pasteInfo.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        val pasteBtn = Button(this)
        pasteBtn.text = "Paste here"
        pasteBtn.setOnClickListener { paste() }
        val cancelBtn = Button(this)
        cancelBtn.text = "Cancel"
        cancelBtn.setOnClickListener {
            Session.clearClip()
            refreshPasteBar()
        }
        pasteBar.addView(pasteInfo)
        pasteBar.addView(pasteBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        pasteBar.addView(cancelBtn, LinearLayout.LayoutParams(WRAP, WRAP))

        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(selBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(body)
        root.addView(pasteBar, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        applyViewMode()
        refreshPasteBar()
    }

    /** HF par video thumbnails band: MediaMetadataRetriever Authorization header redirect ke baad CDN par bhi bhej sakta hai. */
    private fun applyThumbFlag() {
        adapter.videoThumbs = videoThumbs && store == Store.AIC
    }

    private fun spanCount(): Int =
        if (gridMode) max(3, resources.configuration.screenWidthDp / 120) else 1

    private fun applyViewMode() {
        adapter.grid = gridMode
        gridLm.spanCount = spanCount()
        adapter.thumbPx = if (gridMode) resources.displayMetrics.widthPixels / spanCount() else dp(56)
        viewBtn.text = if (gridMode) "☰" else "▦"
        adapter.notifyDataSetChanged()
    }

    private fun toggleView() {
        gridMode = !gridMode
        Session.setBool(this, "grid", gridMode)
        applyViewMode()
    }

    private fun updateHeader() {
        titleTv.text = if (currentUrl == rootUrl) store.label
        else Uri.decode(currentUrl.trimEnd('/').substringAfterLast('/'))
        val folders = entries.count { it.isDir }
        subTv.text = "${store.icon} ${store.label} · $folders folders · ${entries.size - folders} files"
    }

    // ───────────────────────── loading ─────────────────────────

    private fun sorted(l: List<Entry>): List<Entry> {
        val cmp: Comparator<Entry> = when (sortMode) {
            1 -> Comparator { a, b -> natural(b.name, a.name) }
            2 -> Comparator { a, b -> b.modified.compareTo(a.modified) }
            3 -> Comparator { a, b -> a.modified.compareTo(b.modified) }
            4 -> Comparator { a, b -> b.size.compareTo(a.size) }
            else -> Comparator { a, b -> natural(a.name, b.name) }
        }
        return l.sortedWith(compareBy<Entry> { !it.isDir }.then(cmp))
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: "error"
        return if (store == Store.HF && m.contains("503")) "HF Bucket server par band / configured nahi hai (503)" else m
    }

    private fun load(url: String, push: Boolean) {
        val token = ++loadToken
        swipe.isRefreshing = true
        emptyTv.visibility = View.GONE
        io.execute {
            try {
                val list = Dav.list(url)
                runOnUiThread {
                    if (token != loadToken || isFinishing) return@runOnUiThread
                    val changed = url != currentUrl
                    if (push && currentUrl.isNotEmpty() && changed) stack.add(currentUrl)
                    currentUrl = url
                    if (selecting) exitSelect(false)
                    entries = sorted(list)
                    adapter.items = entries
                    adapter.notifyDataSetChanged()
                    if (changed) rv.scrollToPosition(0)
                    swipe.isRefreshing = false
                    updateHeader()
                    if (entries.isEmpty()) {
                        emptyTv.text = "Folder khaali hai"
                        emptyTv.visibility = View.VISIBLE
                    }
                }
            } catch (e: DavAuthException) {
                runOnUiThread {
                    Toast.makeText(this, "Login galat / expire — dobara login karo", Toast.LENGTH_LONG).show()
                    Session.clearPass(this)
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (token != loadToken || isFinishing) return@runOnUiThread
                    swipe.isRefreshing = false
                    val msg = friendly(e)
                    Toast.makeText(this, "Error: $msg", Toast.LENGTH_LONG).show()
                    if (entries.isEmpty()) {
                        emptyTv.text = "Load nahi hua\n$msg\n\nNeeche kheench kar refresh karo"
                        emptyTv.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun goBack() {
        if (stack.isNotEmpty()) {
            val prev = stack.removeAt(stack.size - 1)
            load(prev, false)
        } else {
            finish()
        }
    }

    private fun reload() = load(if (currentUrl.isEmpty()) rootUrl else currentUrl, false)

    // ───────────────────────── open / play ─────────────────────────

    private fun onOpen(e: Entry) {
        if (selecting) {
            toggle(e)
            return
        }
        when (e.kind) {
            Kind.DIR -> load(e.url, true)
            Kind.IMAGE -> {
                val imgs = entries.filter { it.kind == Kind.IMAGE }
                Queue.images = imgs
                val i = imgs.indexOfFirst { it.url == e.url }.coerceAtLeast(0)
                startActivity(Intent(this, ImageViewerActivity::class.java).putExtra("index", i))
            }
            Kind.VIDEO, Kind.AUDIO -> {
                val same = entries.filter { it.kind == e.kind }
                Queue.media = same
                Queue.mediaIndex = same.indexOfFirst { it.url == e.url }.coerceAtLeast(0)
                Queue.pending = true
                startActivity(
                    Intent(this, PlayerActivity::class.java).putExtra("video", e.kind == Kind.VIDEO)
                )
            }
            Kind.OTHER -> {
                AlertDialog.Builder(this)
                    .setTitle(e.name)
                    .setMessage("Is file ka viewer nahi hai. Download karo?")
                    .setPositiveButton("Download") { _, _ -> download(e) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ───────────────────────── multi-select ─────────────────────────

    private fun onLongPress(e: Entry) {
        if (selecting) toggle(e) else enterSelect(e)
    }

    private fun enterSelect(e: Entry) {
        selecting = true
        selected.clear()
        selected[e.url] = e
        refreshSelUi(null)
    }

    private fun toggle(e: Entry) {
        if (selected.remove(e.url) == null) selected[e.url] = e
        if (selected.isEmpty()) exitSelect() else refreshSelUi(e.url)
    }

    private fun exitSelect(redraw: Boolean = true) {
        selecting = false
        selected.clear()
        if (redraw) refreshSelUi(null) else applySelVisibility()
    }

    private fun selectAll() {
        for (e in entries) selected[e.url] = e
        refreshSelUi(null)
    }

    private fun applySelVisibility() {
        header.visibility = if (selecting) View.GONE else View.VISIBLE
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        swipe.isEnabled = !selecting
        adapter.selecting = selecting
    }

    private fun refreshSelUi(changedUrl: String?) {
        applySelVisibility()
        selCount.text = "${selected.size} chune"
        if (changedUrl != null) {
            val i = entries.indexOfFirst { it.url == changedUrl }
            if (i >= 0) adapter.notifyItemChanged(i) else adapter.notifyDataSetChanged()
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    private fun selMore() {
        if (selected.size != 1) {
            Toast.makeText(this, "Rename / Download / Info ke liye ek hi item chuno", Toast.LENGTH_SHORT).show()
            return
        }
        val e = selected.values.first()
        val opts = ArrayList<String>()
        if (!e.isDir) opts.add(optDownload)
        opts.add(optRename)
        opts.add(optInfo)
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(opts.toTypedArray()) { _, i ->
                when (opts[i]) {
                    optDownload -> download(e)
                    optRename -> rename(e)
                    optInfo -> info(e)
                }
            }
            .show()
    }

    private val optDownload = "⬇ Download"
    private val optRename = "✏ Rename"
    private val optInfo = "ℹ Info"

    private fun info(e: Entry) {
        val msg = "Naam: ${e.name}\n" +
            "Storage: ${store.label}\n" +
            (if (e.isDir) "Type: Folder\n" else "Size: ${fmtSize(e.size)}\n") +
            "Badla: ${fmtDate(e.modified)}\n\n${Storages.pathOf(store, e.url)}"
        AlertDialog.Builder(this).setTitle("Info").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun download(e: Entry) {
        Toast.makeText(this, "Download shuru ho raha hai…", Toast.LENGTH_SHORT).show()
        io.execute {
            var url = e.url
            var withAuth = true
            if (store == Store.HF) {
                // CDN link pehle nikaalo: apna Authorization header CDN par nahi jana chahiye
                val cdn = Storages.cdnTarget(e.url)
                if (cdn != null) {
                    url = cdn
                    withAuth = false
                }
            }
            runOnUiThread {
                try {
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle(e.name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MyFiles/" + e.name)
                    if (withAuth) req.addRequestHeader("Authorization", Session.auth())
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this, "Download shuru → Downloads/MyFiles", Toast.LENGTH_SHORT).show()
                } catch (ex: Exception) {
                    Toast.makeText(this, "Download nahi hua: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ───────────────────────── copy / move / paste ─────────────────────────

    private fun doClip(move: Boolean) {
        if (selected.isEmpty()) return
        val items = selected.values.map { ClipItem(store, Storages.pathOf(store, it.url), it.name, it.isDir) }
        Session.setClip(items, move)
        val n = items.size
        exitSelect()
        refreshPasteBar()
        Toast.makeText(
            this,
            "$n item ${if (move) "move" else "copy"} ke liye chune — ab destination folder / storage me jaake 'Paste here' dabao",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshPasteBar() {
        val c = Session.clip
        if (c.isEmpty()) {
            pasteBar.visibility = View.GONE
            return
        }
        val mode = if (Session.clipMove) "✂ Move" else "📋 Copy"
        pasteInfo.text = "$mode · ${c.size} item · ${c[0].store.label} se"
        pasteBar.visibility = View.VISIBLE
    }

    private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

    private fun paste() {
        val items = Session.clip
        if (items.isEmpty()) return
        val move = Session.clipMove
        val dest = Storages.pathOf(store, if (currentUrl.isEmpty()) rootUrl else currentUrl)
        if (move && items.all { it.store == store && parentOf(it.path) == dest.trimEnd('/') }) {
            Toast.makeText(this, "Ye items pehle se isi folder me hain", Toast.LENGTH_SHORT).show()
            return
        }
        val names = entries.map { it.name }.toHashSet()
        if (items.any { it.name in names }) {
            askConflict { oc -> startXfer(items, move, oc, dest) }
        } else {
            startXfer(items, move, "skip", dest)
        }
    }

    private fun askConflict(onChoice: (String) -> Unit) {
        val labels = arrayOf(
            "Skip: jo pehle se hai use rehne do",
            "Rename: naya naam do (name (1))",
            "Overwrite: purani file badal do"
        )
        val values = arrayOf("skip", "rename", "overwrite")
        AlertDialog.Builder(this)
            .setTitle("Is naam ki file / folder yahan pehle se hai")
            .setItems(labels) { _, i -> onChoice(values[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startXfer(items: List<ClipItem>, move: Boolean, onConflict: String, dest: String) {
        val destPath = if (dest.endsWith("/")) dest else "$dest/"
        swipe.isRefreshing = true
        io.execute {
            try {
                val id = Xfer.start(items, store, destPath, move, onConflict)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    swipe.isRefreshing = false
                    if (move) {
                        Session.clearClip()
                        refreshPasteBar()
                    }
                    showProgress(id, (if (move) "Move" else "Copy") + " → " + store.label + ":" + destPath)
                }
            } catch (e: DavAuthException) {
                runOnUiThread {
                    swipe.isRefreshing = false
                    Toast.makeText(this, "Login galat / expire — dobara login karo", Toast.LENGTH_LONG).show()
                    Session.clearPass(this)
                    startActivity(
                        Intent(this, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    swipe.isRefreshing = false
                    Toast.makeText(this, "❌ " + (e.message ?: "transfer shuru nahi hua"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showProgress(id: String, title: String) {
        closeProgress()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(12), dp(20), dp(4))
        val msg = TextView(this)
        msg.text = "Shuru ho raha hai…"
        msg.textSize = 14f
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.isIndeterminate = true
        val blp = LinearLayout.LayoutParams(MATCH, WRAP)
        blp.topMargin = dp(12)
        box.addView(msg, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(bar, blp)

        val d = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Background me chalne do", null)
            .setNegativeButton("Cancel job", null)
            .setNeutralButton("Transfers", null)
            .create()
        d.setCanceledOnTouchOutside(false)
        d.setOnDismissListener {
            if (progDlg === d) {
                progDlg = null
                progMsg = null
                progBar = null
            }
        }
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { d.dismiss() }
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            startActivity(Intent(this, TransfersActivity::class.java))
        }
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            msg.text = "Cancel ho raha hai…"
            io.execute {
                try {
                    Xfer.cancel(id)
                } catch (e: Exception) {
                }
            }
        }
        progDlg = d
        progMsg = msg
        progBar = bar
        watch(id, 0)
    }

    private fun closeProgress() {
        progDlg?.dismiss()
        progDlg = null
        progMsg = null
        progBar = null
    }

    /** Job ka status ~1.5 s par poll. Dialog band ho to bhi chalta rahta hai (khatam hone par listing refresh). */
    private fun watch(id: String, fails: Int) {
        if (isFinishing || isDestroyed) return
        io.execute {
            var job: XferJob? = null
            var lost = false
            try {
                job = Xfer.status(id)
            } catch (e: XferException) {
                if (e.code == 404) lost = true
            } catch (e: Exception) {
            }
            val got: XferJob? = job
            val gone: Boolean = lost
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (gone) {
                    closeProgress()
                    Toast.makeText(this, "Job nahi mili (server restart hua?) — listing refresh karo", Toast.LENGTH_LONG).show()
                    reload()
                    return@runOnUiThread
                }
                if (got == null) {
                    if (fails >= 5) {
                        closeProgress()
                        Toast.makeText(
                            this,
                            "Progress nahi mil raha. Job server par chal rahi hai, Transfers screen dekho",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        ui.postDelayed({ watch(id, fails + 1) }, 3000)
                    }
                    return@runOnUiThread
                }
                val j: XferJob = got
                val pct = j.pct
                progBar?.let { b ->
                    if (pct < 0) {
                        b.isIndeterminate = true
                    } else {
                        b.isIndeterminate = false
                        b.progress = pct
                    }
                }
                progMsg?.text = j.stateLabel() + (if (pct >= 0) " · $pct%" else "") + "\n" + j.line() +
                    (if (j.current.isNotEmpty()) "\n" + j.current else "")
                if (j.active) {
                    ui.postDelayed({ watch(id, 0) }, 1500)
                } else {
                    finishJob(j)
                }
            }
        }
    }

    private fun finishJob(j: XferJob) {
        closeProgress()
        when (j.state) {
            "done" -> Toast.makeText(
                this,
                "✅ Ho gaya: ${j.filesDone} file" + (if (j.filesSkipped > 0) " (${j.filesSkipped} skip)" else ""),
                Toast.LENGTH_LONG
            ).show()
            "cancelled" -> Toast.makeText(this, "⛔ Transfer cancel hua", Toast.LENGTH_LONG).show()
            else -> {
                val txt = if (j.errors.isEmpty()) "Kuch files nahi ho payin" else j.errors.take(8).joinToString("\n• ", "• ")
                AlertDialog.Builder(this)
                    .setTitle("⚠ Transfer me dikkat (${j.filesDone}/${j.filesTotal} ho gayi)")
                    .setMessage(txt)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        if (!selecting) reload()
    }

    // ───────────────────────── file operations ─────────────────────────

    private fun runOp(okMsg: String, op: () -> Unit) {
        swipe.isRefreshing = true
        io.execute {
            val err: String? = try {
                op()
                null
            } catch (e: DavAuthException) {
                "Login galat"
            } catch (e: Exception) {
                e.message ?: "error"
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, err?.let { "❌ $it" } ?: okMsg, Toast.LENGTH_LONG).show()
                if (selecting) exitSelect()
                reload()
            }
        }
    }

    private fun askText(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this)
        et.setSingleLine()
        et.setText(initial)
        et.setSelection(et.text.length)
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty() && !t.contains('/')) onOk(t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newFolder() {
        val dir = currentUrl
        askText("Naya folder", "") { name ->
            runOp("Folder ban gaya") { Dav.mkcol(dir + Uri.encode(name)) }
        }
    }

    private fun rename(e: Entry) {
        if (store == Store.HF && e.isDir) {
            // /xfer folder ko usi naam se hi move karta hai, naya naam nahi de sakta
            AlertDialog.Builder(this)
                .setTitle("HF folder rename")
                .setMessage("HF Bucket me folder ka naam badalna abhi supported nahi hai.\n\nFolder ko doosre folder me bhejna ho to Move (✂) use karo.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val dir = currentUrl
        askText("Rename", e.name) { name ->
            if (name != e.name) {
                runOp("Rename ho gaya") {
                    Dav.move(e.url, dir + Uri.encode(name) + (if (e.isDir) "/" else ""))
                }
            }
        }
    }

    private fun confirmDelete(list: List<Entry>) {
        if (list.isEmpty()) return
        val hasDir = list.any { it.isDir }
        val what = if (list.size == 1) list[0].name else "${list.size} items"
        val title: String
        val msg: String
        if (store == Store.HF) {
            title = "⚠ HF Bucket se delete?"
            msg = what + "\n\n⚠ HF Bucket se PERMANENTLY delete hoga. Trash nahi hai, wapas nahi aayega." +
                (if (hasDir) "\nFolder ho to uske andar ki saari files bhi jayengi." else "") +
                "\n\nDhyan: is bucket me Trading / app ka data bhi ho sakta hai."
        } else {
            title = "Delete?"
            msg = what + (if (hasDir) "\n\n⚠ Poora folder aur uske andar ki saari files hat jayengi." else "")
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
                runOp("Delete ho gaya") {
                    var fail = 0
                    var last = ""
                    for (e in list) {
                        try {
                            Dav.delete(e.url)
                        } catch (ex: DavAuthException) {
                            throw ex
                        } catch (ex: Exception) {
                            fail++
                            last = ex.message ?: ""
                        }
                    }
                    if (fail > 0) throw IOException("$fail item delete nahi hue ($last)")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun upload(uris: List<Uri>) {
        val dir = currentUrl
        swipe.isRefreshing = true
        io.execute {
            var ok = 0
            for ((i, u) in uris.withIndex()) {
                var name = "file_" + System.currentTimeMillis()
                var size = -1L
                try {
                    contentResolver.query(u, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (ni >= 0) name = c.getString(ni) ?: name
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                } catch (e: Exception) {
                }
                val label = "⬆ ${i + 1}/${uris.size}: $name"
                runOnUiThread { subTv.text = label }
                try {
                    Dav.put(dir + Uri.encode(name), UriBody(this, u, size, contentResolver.getType(u)))
                    ok++
                } catch (e: Exception) {
                    val fn = name
                    runOnUiThread {
                        Toast.makeText(this, "Upload fail: $fn (${e.message})", Toast.LENGTH_LONG).show()
                    }
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, "Upload: $ok/${uris.size} ho gaye", Toast.LENGTH_LONG).show()
                reload()
            }
        }
    }

    // ───────────────────────── menu / storage / update / logout ─────────────────────────

    private fun switchStorage() {
        val all = Store.values()
        val labels = all.map { it.icon + " " + it.label + (if (it == store) "  (abhi yahan)" else "") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Storage chuno")
            .setItems(labels) { _, i ->
                val s = all[i]
                if (s != store) {
                    startActivity(Intent(this, BrowserActivity::class.java).putExtra("store", s.id))
                    finish()
                }
            }
            .show()
    }

    private fun showMenu() {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("📁 Naya folder" to { newFolder() })
        acts.add("⬆ Upload (files chuno)" to { pickFiles.launch(arrayOf("*/*")) })
        acts.add("🔁 Storage badlo" to { switchStorage() })
        acts.add("📊 Transfers" to { startActivity(Intent(this, TransfersActivity::class.java)) })
        acts.add("↕ Sort: ${sortNames[sortMode]}" to { showSort() })
        acts.add(
            ("🎞 Video thumbnails: " + (if (videoThumbs) "ON" else "OFF") +
                (if (store == Store.HF) " (HF par hamesha OFF)" else "")) to {
                videoThumbs = !videoThumbs
                Session.setBool(this, "vthumbs", videoThumbs)
                applyThumbFlag()
                adapter.notifyDataSetChanged()
            }
        )
        acts.add("🧹 Thumbnail cache saaf karo" to {
            Thumbs.clear()
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Cache saaf", Toast.LENGTH_SHORT).show()
        })
        acts.add("🔄 Update check karo" to {
            UpdateChecker.check(this) { info ->
                if (info == null) {
                    Toast.makeText(this, "Latest version hai (ya check nahi hua)", Toast.LENGTH_LONG).show()
                } else {
                    showUpdate(info)
                }
            }
        })
        acts.add("🚪 Logout / server badlo" to { logout() })
        AlertDialog.Builder(this)
            .setItems(acts.map { it.first }.toTypedArray()) { _, which -> acts[which].second() }
            .show()
    }

    private fun showSort() {
        AlertDialog.Builder(this)
            .setTitle("Sort")
            .setSingleChoiceItems(sortNames, sortMode) { d, which ->
                sortMode = which
                Session.setInt(this, "sort", which)
                entries = sorted(entries)
                adapter.items = entries
                adapter.notifyDataSetChanged()
                d.dismiss()
            }
            .show()
    }

    private fun showUpdate(info: UpdateChecker.Info) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Naya update mila")
            .setMessage("Update karke install karo?")
            .setPositiveButton("Update karo", null)
            .setNegativeButton("Baad me", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
            if (!UpdateChecker.ensureInstallPermission(this)) return@setOnClickListener
            btn.isEnabled = false
            dlg.setMessage("Download ho raha hai…")
            UpdateChecker.downloadAndInstall(
                this, info,
                { p -> dlg.setMessage("Download $p%") },
                { err ->
                    btn.isEnabled = true
                    dlg.setMessage("Error: $err")
                },
                { intent -> startActivity(intent) }
            )
        }
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
