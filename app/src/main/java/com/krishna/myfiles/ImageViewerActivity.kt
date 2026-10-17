package com.krishna.myfiles

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class ImageViewerActivity : AppCompatActivity() {

    private var items: List<Entry> = emptyList()
    private lateinit var topBar: TextView
    private var barsVisible = true

    inner class PageVH(
        val box: FrameLayout,
        val iv: ZoomImageView,
        val pb: ProgressBar
    ) : RecyclerView.ViewHolder(box)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = Queue.images
        if (items.isEmpty()) {
            finish()
            return
        }
        val start = intent.getIntExtra("index", 0).coerceIn(0, items.size - 1)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val pager = ViewPager2(this)
        pager.offscreenPageLimit = 1
        pager.adapter = object : RecyclerView.Adapter<PageVH>() {
            override fun getItemCount(): Int = items.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
                val box = FrameLayout(this@ImageViewerActivity)
                box.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                val iv = ZoomImageView(this@ImageViewerActivity)
                iv.onTap = { toggleBars() }
                box.addView(iv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                val pb = ProgressBar(this@ImageViewerActivity)
                box.addView(pb, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                ))
                return PageVH(box, iv, pb)
            }

            override fun onBindViewHolder(holder: PageVH, position: Int) {
                val e = items[position]
                holder.iv.setImageDrawable(null)
                holder.iv.tag = e.url
                holder.pb.visibility = View.VISIBLE
                Thumbs.loadFull(e.url, 2000) { bmp ->
                    if (holder.iv.tag == e.url) {
                        holder.pb.visibility = View.GONE
                        if (bmp != null) {
                            holder.iv.setImageBitmap(bmp)
                        } else {
                            Toast.makeText(this@ImageViewerActivity, "Image load nahi hui: ${e.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        root.addView(pager, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        topBar = TextView(this)
        topBar.setTextColor(Color.WHITE)
        topBar.textSize = 14f
        topBar.maxLines = 1
        topBar.setBackgroundColor(0x88000000.toInt())
        topBar.setPadding(dp(14), dp(36), dp(14), dp(10))
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        setContentView(root)
        pager.setCurrentItem(start, false)
        updateTitle(start)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTitle(position)
            }
        })
    }

    private fun updateTitle(pos: Int) {
        topBar.text = "${pos + 1}/${items.size}   ${items[pos].name}"
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        topBar.visibility = if (barsVisible) View.VISIBLE else View.GONE
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (barsVisible) c.show(WindowInsetsCompat.Type.systemBars())
        else c.hide(WindowInsetsCompat.Type.systemBars())
    }
}
