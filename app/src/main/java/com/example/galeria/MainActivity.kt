package com.example.galeria

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

class Square(c: android.content.Context) : ImageView(c) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

class MainActivity : Activity() {
    private val uris = ArrayList<Uri>()
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(k: String, v: Bitmap) = v.byteCount
    }
    private lateinit var grid: GridView
    private lateinit var viewer: ImageView
    private lateinit var empty: TextView
    private var current = -1

    private val permission = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v, i ->
                v.setPadding(0, i.systemWindowInsetTop, 0, i.systemWindowInsetBottom); i
            }
        }
        content.addView(TextView(this).apply {
            text = "Galeria"; textSize = 24f; setTextColor(Color.BLACK)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(2); verticalSpacing = dp(2)
            adapter = Adapter()
            setOnItemClickListener { _, _, i, _ -> open(i) }
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, -1))
        empty = TextView(this).apply {
            text = "Nenhuma foto encontrada"; setTextColor(Color.GRAY)
            gravity = android.view.Gravity.CENTER; visibility = View.GONE
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(empty, FrameLayout.LayoutParams(-1, -1))

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vx < -500 && current < uris.size - 1) show(current + 1)
                else if (vx > 500 && current > 0) show(current - 1)
                return true
            }
        })
        viewer = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
        }
        root.addView(viewer, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) loadList()
        else requestPermissions(arrayOf(permission), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) loadList()
        else { empty.text = "Precisamos de acesso às suas fotos."; empty.visibility = View.VISIBLE }
    }

    private fun loadList() {
        pool.execute {
            val list = ArrayList<Uri>()
            val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) list += ContentUris.withAppendedId(col, c.getLong(0))
            }
            ui.post {
                uris.clear(); uris.addAll(list)
                (grid.adapter as BaseAdapter).notifyDataSetChanged()
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = uris.size
        override fun getItem(p: Int) = uris[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val iv = (convert as? Square) ?: Square(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFEEEEEE.toInt())
            }
            val uri = uris[p]
            iv.tag = uri
            val hit = cache.get("$uri#300")
            if (hit != null) iv.setImageBitmap(hit)
            else {
                iv.setImageBitmap(null)
                load(uri, 300) { if (iv.tag == uri) iv.setImageBitmap(it) }
            }
            return iv
        }
    }

    private fun open(i: Int) { viewer.visibility = View.VISIBLE; show(i) }

    private fun show(i: Int) {
        current = i
        val uri = uris[i]
        viewer.setImageBitmap(cache.get("$uri#300"))
        load(uri, 1600) { if (current == i) viewer.setImageBitmap(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (viewer.visibility == View.VISIBLE) { viewer.visibility = View.GONE; current = -1 }
        else super.onBackPressed()
    }

    private fun load(uri: Uri, size: Int, done: (Bitmap) -> Unit) {
        pool.execute {
            val key = "$uri#$size"
            val bmp = cache.get(key) ?: decode(uri, size)?.also { cache.put(key, it) }
            if (bmp != null) ui.post { done(bmp) }
        }
    }

    private fun decode(uri: Uri, size: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (bounds.outWidth / (s * 2) >= size && bounds.outHeight / (s * 2) >= size) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        var b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        val deg = contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (b != null && deg != 0f)
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        b
    } catch (e: Exception) { null }
}
