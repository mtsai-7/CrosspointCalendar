package io.github.mtsai7.xcal

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.mtsai7.xcal.image.ImageConverter
import io.github.mtsai7.xcal.image.PinnedImageStore
import io.github.mtsai7.xcal.image.XcalBitmap
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

/**
 * "Show on X3": converts a shared or picked image to the X3's pinned-image BMP
 * (spec §4.4), previews it, and pins it. Opened from the share sheet
 * (ACTION_SEND image/...) or from MainActivity, which lets the user pick one.
 */
class ImageActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var preview: ImageView
    private lateinit var info: TextView
    private lateinit var showButton: Button
    private lateinit var modeGroup: RadioGroup
    private var source: Bitmap? = null
    // Not "rotation": inside a View's apply {} that name is the View's own rotation.
    private var imageRotation = 0
    private var mode = XcalBitmap.Mode.SHARP
    private var converted: ImageConverter.Converted? = null
    private var expiry: Duration? = null
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val uri = imageUri(intent)
        if (uri != null) {
            load(uri)
        } else if (savedInstanceState == null) {
            // Opened from the app: let the user pick an image.
            val pick = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(Intent.createChooser(pick, getString(R.string.image_pick)), REQUEST_PICK)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Platform Activity API; the app uses no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) finish() else load(uri)
    }

    private fun imageUri(intent: Intent): Uri? =
        if (intent.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else {
            intent.data
        }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { setStroke(2, Color.GRAY) }
            setPadding(2, 2, 2, 2)
            // The X3 screen is 2:3; keep the preview at that shape while loading.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55).toInt())
        }
        column.addView(preview)
        info = TextView(this).apply { setText(R.string.image_loading) }
        column.addView(info)

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(radio(MODE_SHARP_ID, R.string.image_mode_sharp))
            addView(radio(MODE_PHOTO_ID, R.string.image_mode_photo))
            setOnCheckedChangeListener { _, id ->
                mode = if (id == MODE_PHOTO_ID) XcalBitmap.Mode.PHOTO else XcalBitmap.Mode.SHARP
                reconvert()
            }
        }
        column.addView(modeGroup)
        column.addView(Button(this).apply {
            setText(R.string.image_rotate)
            setOnClickListener {
                imageRotation = (imageRotation + 90) % 360
                reconvert()
            }
        })

        column.addView(TextView(this).apply {
            setText(R.string.image_expiry_label)
            setPadding(0, pad / 2, 0, 0)
        })
        val expiries = listOf(null, Duration.ofHours(12), Duration.ofHours(24), Duration.ofHours(48))
        val expiryLabels = listOf(R.string.image_expiry_never, R.string.image_expiry_12h, R.string.image_expiry_24h,
            R.string.image_expiry_48h)
        column.addView(RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            expiryLabels.forEachIndexed { i, label -> addView(radio(EXPIRY_BASE_ID + i, label)) }
            check(EXPIRY_BASE_ID)
            setOnCheckedChangeListener { _, id -> expiry = expiries[id - EXPIRY_BASE_ID] }
        })

        showButton = Button(this).apply {
            setText(R.string.image_show)
            isEnabled = false
            setOnClickListener { pin() }
        }
        column.addView(showButton)
        column.addView(Button(this).apply {
            setText(android.R.string.cancel)
            setOnClickListener { finish() }
        })
        column.addView(TextView(this).apply {
            setText(R.string.image_hint)
            setPadding(0, pad / 2, 0, 0)
        })

        val root = ScrollView(this).apply { addView(column) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            column.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun radio(id: Int, label: Int) = RadioButton(this).apply {
        this.id = id
        setText(label)
    }

    private fun load(uri: Uri) {
        worker.execute {
            val bitmap = try {
                ImageConverter.decode(contentResolver, uri)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot decode $uri", e)
                null
            }
            runOnUiThread {
                if (bitmap == null) {
                    Toast.makeText(this, R.string.image_error, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                source = bitmap
                imageRotation = ImageConverter.autoRotation(bitmap)
                // Photos look best in grays; screenshots, text and codes in black and
                // white. Nothing is checked yet, so this fires the listener, which converts.
                modeGroup.check(if (looksLikePhoto(bitmap)) MODE_PHOTO_ID else MODE_SHARP_ID)
            }
        }
    }

    /** True when a sizeable share of pixels are mid-grays (sampled). */
    private fun looksLikePhoto(bitmap: Bitmap): Boolean {
        var mid = 0
        var total = 0
        val step = maxOf(1, maxOf(bitmap.width, bitmap.height) / 200)
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val c = bitmap.getPixel(x, y)
                val l = (77 * Color.red(c) + 150 * Color.green(c) + 29 * Color.blue(c)) shr 8
                if (l in 48..207) mid++
                total++
            }
        }
        return total > 0 && mid * 4 > total
    }

    private fun reconvert() {
        val src = source ?: return
        val gen = ++generation
        val rot = imageRotation
        val m = mode
        showButton.isEnabled = false
        info.setText(R.string.image_converting)
        worker.execute {
            val result = ImageConverter.convert(src, rot, m)
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                converted = result
                preview.setImageBitmap(result.preview)
                info.text = getString(R.string.image_ready, result.bmp.size / 1024)
                showButton.isEnabled = true
            }
        }
    }

    private fun pin() {
        val result = converted ?: return
        val expiresAt = expiry?.let { Instant.now().plus(it) }
        showButton.isEnabled = false
        worker.execute {
            val ok = try {
                PinnedImageStore.pin(this, result.bmp, expiresAt)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Pinning failed", e)
                false
            }
            runOnUiThread {
                if (ok) {
                    if (Prefs.enabled(this)) XcalService.start(this)
                    Toast.makeText(this, R.string.image_pinned, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, R.string.image_error, Toast.LENGTH_LONG).show()
                    showButton.isEnabled = true
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImageActivity"
        private const val REQUEST_PICK = 1
        private val MODE_SHARP_ID = View.generateViewId()
        private val MODE_PHOTO_ID = View.generateViewId()
        private const val EXPIRY_BASE_ID = 0x7000
    }
}
