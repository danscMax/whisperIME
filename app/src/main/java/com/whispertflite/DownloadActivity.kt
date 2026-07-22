package com.whispertflite

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.whispertflite.databinding.ActivityDownloadBinding
import com.whispertflite.models.DeviceProfile
import com.whispertflite.models.ModelDownloadManager
import com.whispertflite.models.ModelInfo
import com.whispertflite.models.ModelRecommender
import com.whispertflite.utils.ThemeUtils
import java.util.Locale

/**
 * First-run guided wizard: Welcome → Get ready (mic + enable the keyboard) → Recommended model (chosen
 * for this device) → First test → Done. Returning users (a model already present) skip straight into the
 * app. Downloads reuse [ModelDownloadManager]; the recommendation reuses [DeviceProfile]/[ModelRecommender].
 */
class DownloadActivity : AppCompatActivity(), ModelDownloadManager.Listener {

    private var binding: ActivityDownloadBinding? = null
    private lateinit var manager: ModelDownloadManager

    private lateinit var recommended: ModelInfo
    private var step = 0
    private var tried = false
    private var downloading = false

    companion object {
        /** Replay the wizard even though a model is already installed (Settings → run it again). */
        const val EXTRA_REPLAY = "replay"
        private const val STEP_COUNT = 5
        private const val STEP_SETUP = 1
        private const val STEP_MODEL = 2
        private const val STEP_TRY = 3
    }

    /** The Get-ready step must not be passed until the two things dictation actually needs are in place. */
    private fun canAdvance() = !downloading && (step != STEP_SETUP || (micGranted() && imeEnabled()))

    private val micRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSetup() }

    /** Text arriving in the rehearsal field means the user pulled off the real gesture — celebrate it. */
    private fun onRehearsalText(text: CharSequence?) {
        if (text.isNullOrBlank() || tried) return
        tried = true
        binding?.tryOk?.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeUtils.applyPalette(this)
        ThemeUtils.applyGlass(this)
        val b = ActivityDownloadBinding.inflate(layoutInflater)
        binding = b
        setContentView(b.root)
        ThemeUtils.setStatusBarAppearance(this)

        manager = ModelDownloadManager.get(this)

        // Returning user with a model already installed: don't re-onboard — unless they asked to replay.
        if (manager.hasAnyModel() && !intent.getBooleanExtra(EXTRA_REPLAY, false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val orbBright = MaterialColors.getColor(b.onboardingOrb, androidx.appcompat.R.attr.colorPrimary)
        val orbDeep = MaterialColors.getColor(b.onboardingOrb, com.google.android.material.R.attr.colorPrimaryContainer)
        b.onboardingOrb.setColors(orbBright, orbDeep)
        b.doneOrb.setColors(orbBright, orbDeep)

        val prof = DeviceProfile.detect(this)
        val reco = ModelRecommender.recommend(prof, isRussianUi())
        recommended = reco.model
        b.modelName.text = reco.model.label(this)
        b.modelSize.text = Formatter.formatShortFileSize(this, reco.model.sizeBytes)
        b.modelReason.text = getString(reco.reasonResId)
        b.deviceSummary.text = getString(
            R.string.onb_device_summary, prof.cores,
            Formatter.formatShortFileSize(this, prof.ramMb * 1024 * 1024)
        )

        b.btnNext.setOnClickListener { onNext() }
        b.btnBack.setOnClickListener { goTo(step - 1) }
        b.btnMic.setOnClickListener {
            if (!micGranted()) micRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
        b.btnKb.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        b.btnChange.setOnClickListener {
            startActivity(Intent(this, com.whispertflite.models.ModelCatalogActivity::class.java))
        }
        // No canned dialog here: focus the real field and open the system keyboard picker, so the
        // rehearsal IS the everyday gesture (keyboard → mic/globe → Sweet Whisper → speak).
        b.btnTry.setOnClickListener {
            if (!micGranted()) { micRequest.launch(Manifest.permission.RECORD_AUDIO); return@setOnClickListener }
            b.tryField.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        }
        b.tryField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = onRehearsalText(s)
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        // Recording gesture: hold (press-hold-release) vs tap (tap to start / tap to stop). Picked here,
        // then the test above opens in that gesture; shared with the IME + settings via the recordMode pref.
        // (Auto hands-free is a separate switch in Settings, not part of first-run.)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        b.onbModeGroup.check(when (prefs.getString("recordMode", "hold")) {
            "auto" -> b.onbModeAuto.id
            "tap" -> b.onbModeTap.id
            else -> b.onbModeHold.id
        })
        b.onbModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) prefs.edit().putString("recordMode", when (checkedId) {
                b.onbModeAuto.id -> "auto"
                b.onbModeTap.id -> "tap"
                else -> "hold"
            }).apply()
        }

        goTo(0)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshModel()
    }

    // Only listen while the wizard is actually running. On the returning-user path onCreate calls
    // finish() early and `recommended` stays uninitialized, but the lifecycle still runs onStart — a
    // download event then would hit `recommended.id` (lateinit) and crash. Guard on isFinishing.
    override fun onStart() { super.onStart(); if (!isFinishing) manager.addListener(this) }
    override fun onStop() { super.onStop(); manager.removeListener(this) }

    // ----- step machine -----

    private fun goTo(target: Int) {
        val b = binding ?: return
        step = target.coerceIn(0, STEP_COUNT - 1)
        b.flipper.displayedChild = step
        b.stepperText.text = getString(R.string.onb_step_of, step + 1, STEP_COUNT)
        b.stepperBar.progress = (step + 1) * 100 / STEP_COUNT
        b.btnBack.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        b.btnNext.isEnabled = canAdvance()
        b.btnNext.text = when {
            step == 0 -> getString(R.string.onb_welcome_start)
            step == STEP_COUNT - 1 -> getString(R.string.onb_done_finish)
            step == STEP_MODEL && !manager.hasAnyModel() -> getString(R.string.onb_model_download)
            else -> getString(R.string.onb_next)
        }
        refreshSetup()
        refreshModel()
        // Landing on the rehearsal step: put the caret in the field and raise the keyboard, so the very
        // next thing on screen is the keyboard whose mic button the user is being taught to press.
        if (step == STEP_TRY) {
            b.tryField.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(b.tryField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun onNext() {
        if (step == STEP_MODEL && !manager.hasAnyModel()) { startDownload(); return }
        if (step == STEP_COUNT - 1) { finishWizard(); return }
        goTo(step + 1)
    }

    private fun finishWizard() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ----- per-step refresh -----

    private fun refreshSetup() {
        val b = binding ?: return
        markDone(b.btnMic, micGranted(), R.string.onb_grant)
        markDone(b.btnKb, imeEnabled(), R.string.onb_enable)
        b.btnNext.isEnabled = canAdvance()   // returning from the mic dialog / IME settings lifts the gate
    }

    private fun markDone(btn: com.google.android.material.button.MaterialButton, done: Boolean, actionRes: Int) {
        btn.isEnabled = !done
        btn.text = getString(if (done) R.string.onb_done_check else actionRes)
    }

    private fun refreshModel() {
        val b = binding ?: return
        if (step == STEP_MODEL && manager.hasAnyModel() && !downloading) {
            b.downloadStatus.visibility = View.VISIBLE
            b.downloadStatus.text = getString(R.string.onb_model_downloaded)
            b.downloadProgress.visibility = View.GONE
            b.btnNext.text = getString(R.string.onb_next)
        }
    }

    private fun startDownload() {
        val b = binding ?: return
        downloading = true
        b.btnNext.isEnabled = false
        b.downloadStatus.visibility = View.VISIBLE
        b.downloadProgress.visibility = View.VISIBLE
        b.downloadProgress.isIndeterminate = true
        b.downloadStatus.text = getString(R.string.catalog_starting)
        manager.download(recommended)
    }

    // ----- capability checks -----

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun imeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isRussianUi() =
        resources.configuration.locales[0].language == "ru" || Locale.getDefault().language == "ru"

    // ----- ModelDownloadManager.Listener (main thread) -----

    override fun onProgress(modelId: String, bytes: Long, total: Long, bytesPerSec: Long) {
        if (modelId != recommended.id) return
        val b = binding ?: return
        if (total > 0) {
            b.downloadProgress.isIndeterminate = false
            val pct = (bytes * 100 / total).toInt()
            b.downloadProgress.progress = pct
            b.downloadStatus.text = getString(R.string.onboarding_downloading, pct)
        }
    }

    override fun onDone(modelId: String) {
        if (modelId != recommended.id) return
        downloading = false
        manager.setSelected(modelId)   // activate it now — no "go back to select it" after onboarding (R05)
        goTo(STEP_TRY)
    }

    override fun onError(modelId: String, message: String) {
        if (modelId != recommended.id) return
        val b = binding ?: return
        downloading = false
        b.downloadProgress.visibility = View.GONE
        b.btnNext.isEnabled = true
        b.downloadStatus.visibility = View.VISIBLE
        b.downloadStatus.text = getString(
            if (ModelDownloadManager.ERR_WIFI == message) R.string.catalog_err_wifi
            else R.string.catalog_err_download
        )
    }
}
