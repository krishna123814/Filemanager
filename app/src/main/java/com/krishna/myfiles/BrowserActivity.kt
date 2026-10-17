package com.krishna.myfiles

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class BrowserActivity : AppCompatActivity() {

    private val io: ExecutorService = Executors.newCachedThreadPool()

    private lateinit var adapter: FileAdapter
    private lateinit var gridLm: GridLayoutManager
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var emptyTv: TextView
    private lateinit var viewBtn: TextView

    private var rootUrl = ""
    private var currentUrl = ""
    private val stack = ArrayList<String>()
    private var entries: List<Entry> = emptyList()
    private var loadToken = 0

    private var gridMode = false
    private var sortMode = 0
    private var videoThumbs = true

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

        rootUrl = Dav.withSlash(Session.baseUrl)
        gridMode = Session.bool(this, "grid", false)
        sortMode = Session.int(this, "sort", 0).coerceIn(0, sortNames.size - 1)
        videoThumbs = Session.bool(this, "vthumbs", true)

        buildUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBack()
            }
        })

        val saved = savedInstanceState?.getString("cur")
        val savedStack = savedInstanceState?.getStringArrayList("stack")
        if (savedStack != null) stack.addAll(savedStack)
        load(saved ?: rootUrl, false)

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("cur", currentUrl)
        outState.putStringArrayList("stack", ArrayList(stack))
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ───────────────────────── UI ─────────────────────────

    private fun iconBtn(t: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = t
        b.textSize = 22f
        b.setTextColor(Color.WHITE)
        b.gravity = Gravity.CENTER
        b.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF121212.toInt())

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(0xFF1F1F1F.toInt())
        header.setPadding(dp(4), 0, dp(4), 0)

        val back = iconBtn("←") { goBack() }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        col.setPadding(dp(4), dp(8), dp(4), dp(8))

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
        header.addView(col)
        header.addView(viewBtn)
        header.addView(more)

        adapter = FileAdapter(this, { e -> onOpen(e) }, { e -> onLongPress(e) })
        adapter.videoThumbs = videoThumbs
        gridLm = GridLayoutManager(this, 1)
        rv = RecyclerView(this)
        rv.layoutManager = gridLm
        rv.adapter = adapter

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

        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(body)
        setContentView(root)

        applyViewMode()
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
        titleTv.text = if (currentUrl == rootUrl) "Home"
        else Uri.decode(currentUrl.trimEnd('/').substringAfterLast('/'))
        val folders = entries.count { it.isDir }
        subTv.text = "$folders folders · ${entries.size - folders} files"
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
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    if (entries.isEmpty()) {
                        emptyTv.text = "Load nahi hua\n${e.message}\n\nNeeche kheench kar refresh karo"
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

    // ───────────────────────── open / play ─────────────────────────

    private fun onOpen(e: Entry) {
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

    private val optDownload = "⬇ Download"
    private val optRename = "✏ Rename"
    private val optDelete = "🗑 Delete"
    private val optInfo = "ℹ Info"

    private fun onLongPress(e: Entry) {
        val opts = ArrayList<String>()
        if (!e.isDir) opts.add(optDownload)
        opts.add(optRename)
        opts.add(optDelete)
        opts.add(optInfo)
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setItems(opts.toTypedArray()) { _, i ->
                when (opts[i]) {
                    optDownload -> download(e)
                    optRename -> rename(e)
                    optDelete -> confirmDelete(e)
                    optInfo -> info(e)
                }
            }
            .show()
    }

    private fun info(e: Entry) {
        val msg = "Naam: ${e.name}\n" +
            (if (e.isDir) "Type: Folder\n" else "Size: ${fmtSize(e.size)}\n") +
            "Badla: ${fmtDate(e.modified)}\n\n${e.url}"
        AlertDialog.Builder(this).setTitle("Info").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun download(e: Entry) {
        try {
            val req = DownloadManager.Request(Uri.parse(e.url))
                .addRequestHeader("Authorization", Session.auth())
                .setTitle(e.name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MyFiles/" + e.name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(this, "Download shuru → Downloads/MyFiles", Toast.LENGTH_SHORT).show()
        } catch (ex: Exception) {
            Toast.makeText(this, "Download nahi hua: ${ex.message}", Toast.LENGTH_LONG).show()
        }
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
                load(if (currentUrl.isEmpty()) rootUrl else currentUrl, false)
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
        val dir = currentUrl
        askText("Rename", e.name) { name ->
            if (name != e.name) {
                runOp("Rename ho gaya") {
                    Dav.move(e.url, dir + Uri.encode(name) + (if (e.isDir) "/" else ""))
                }
            }
        }
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage(
                e.name + (if (e.isDir) "\n\n⚠ Poora folder aur uske andar ki saari files hat jayengi." else "")
            )
            .setPositiveButton("Delete") { _, _ ->
                runOp("Delete ho gaya") { Dav.delete(e.url) }
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
                load(if (currentUrl.isEmpty()) rootUrl else currentUrl, false)
            }
        }
    }

    // ───────────────────────── menu / update / logout ─────────────────────────

    private fun showMenu() {
        val labels = arrayOf(
            "📁 Naya folder",
            "⬆ Upload (files chuno)",
            "↕ Sort: ${sortNames[sortMode]}",
            "🎞 Video thumbnails: " + (if (videoThumbs) "ON" else "OFF"),
            "🧹 Thumbnail cache saaf karo",
            "🔄 Update check karo",
            "🚪 Logout / server badlo"
        )
        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> newFolder()
                    1 -> pickFiles.launch(arrayOf("*/*"))
                    2 -> showSort()
                    3 -> {
                        videoThumbs = !videoThumbs
                        Session.setBool(this, "vthumbs", videoThumbs)
                        adapter.videoThumbs = videoThumbs
                        adapter.notifyDataSetChanged()
                    }
                    4 -> {
                        Thumbs.clear()
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this, "Cache saaf", Toast.LENGTH_SHORT).show()
                    }
                    5 -> UpdateChecker.check(this) { info ->
                        if (info == null) {
                            Toast.makeText(this, "Latest version hai (ya check nahi hua)", Toast.LENGTH_LONG).show()
                        } else {
                            showUpdate(info)
                        }
                    }
                    6 -> logout()
                }
            }
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
