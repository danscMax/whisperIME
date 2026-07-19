package com.whispertflite

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.whispertflite.databinding.ActivityDownloadBinding
import com.whispertflite.models.ModelDownloadManager
import com.whispertflite.models.ModelInfo
import com.whispertflite.models.ModelRegistry
import com.whispertflite.utils.ThemeUtils

/**
 * First-run onboarding: pick one recommended model, download it inline, then enter the app.
 * Further models are managed in the catalog. All engines are offline.
 */
class DownloadActivity : AppCompatActivity(), ModelDownloadManager.Listener {

    private var binding: ActivityDownloadBinding? = null
    private lateinit var manager: ModelDownloadManager

    // Recommended default: whisper.cpp base (f16, non-quantized). On this build's runtime-dispatched
    // accelerated CPU backend (armv8.x dotprod/i8mm) it transcribes noticeably faster than TFLite
    // (measured ~2.4x faster than TFLite at equal size), covers 99 languages, and stays silent on
    // silence instead of hallucinating. Quantized (Q5) variants were removed — they recognise worse,
    // especially on the RecognitionService provider path. (armv7/low-end devices fall back to the
    // baseline CPU backend, where TFLite may match it.)
    private val base by lazy { ModelRegistry.byId("gguf-base")!! }
    private val small by lazy { ModelRegistry.byId("tflite-small-topworld")!! }
    private val tiny by lazy { ModelRegistry.byId("tflite-tiny-en")!! }
    private var selected: ModelInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtils.applyPalette(this)
        ThemeUtils.applyGlass(this)
        val b = ActivityDownloadBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ThemeUtils.setStatusBarAppearance(this)

        manager = ModelDownloadManager.get(this)

        // Aurora hero orb: tint from the selected palette (bright accent + deep container).
        b.onboardingOrb.setColors(
            MaterialColors.getColor(b.onboardingOrb, androidx.appcompat.R.attr.colorPrimary),
            MaterialColors.getColor(b.onboardingOrb, com.google.android.material.R.attr.colorPrimaryContainer)
        )

        b.baseSize.text = Formatter.formatShortFileSize(this, base.sizeBytes)
        b.smallSize.text = Formatter.formatShortFileSize(this, small.sizeBytes)
        b.tinySize.text = Formatter.formatShortFileSize(this, tiny.sizeBytes)

        b.cardBase.setOnClickListener { select(base) }
        b.cardSmall.setOnClickListener { select(small) }
        b.cardTiny.setOnClickListener { select(tiny) }
        select(base)

        b.buttonDownload.setOnClickListener { startDownload() }
        b.buttonMore.setOnClickListener {
            startActivity(Intent(this, com.whispertflite.models.ModelCatalogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Model already present (relaunch): skip onboarding.
        if (manager.hasAnyModel()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        manager.addListener(this)
    }

    override fun onStop() {
        super.onStop()
        manager.removeListener(this)
    }

    private fun select(model: ModelInfo) {
        selected = model
        highlight(binding!!.cardBase, model === base)
        highlight(binding!!.cardSmall, model === small)
        highlight(binding!!.cardTiny, model === tiny)
    }

    private fun highlight(card: MaterialCardView, on: Boolean) {
        card.strokeWidth = if (on) (2 * resources.displayMetrics.density).toInt() else 0
        card.strokeColor = MaterialColors.getColor(
            card, androidx.appcompat.R.attr.colorPrimary
        )
    }

    private fun startDownload() {
        val model = selected ?: return
        val b = binding ?: return
        b.buttonDownload.isEnabled = false
        b.downloadStatus.visibility = View.VISIBLE
        b.downloadProgress.visibility = View.VISIBLE
        b.downloadProgress.isIndeterminate = true
        b.downloadStatus.text = getString(R.string.catalog_starting)
        manager.download(model)
    }

    // --- ModelDownloadManager.Listener (main thread) ---

    override fun onProgress(modelId: String, bytes: Long, total: Long, bytesPerSec: Long) {
        if (modelId != selected?.id) return
        val b = binding ?: return
        if (total > 0) {
            b.downloadProgress.isIndeterminate = false
            val pct = (bytes * 100 / total).toInt()
            b.downloadProgress.progress = pct
            b.downloadStatus.text = getString(R.string.onboarding_downloading, pct)
        }
    }

    override fun onDone(modelId: String) {
        if (modelId != selected?.id) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onError(modelId: String, message: String) {
        if (modelId != selected?.id) return
        val b = binding ?: return
        b.downloadProgress.visibility = View.GONE
        b.buttonDownload.isEnabled = true
        b.downloadStatus.text = getString(
            if (ModelDownloadManager.ERR_WIFI == message) R.string.catalog_err_wifi
            else R.string.catalog_err_download
        )
    }
}
