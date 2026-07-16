package com.whispertflite;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;
import com.whispertflite.models.ModelDownloadManager;
import com.whispertflite.utils.LanguagePairAdapter;
import com.whispertflite.utils.ThemeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class WhisperRecognitionServiceSettingsActivity extends AppCompatActivity {
    private static final String TAG = "RecognitionSettings";

    public static final String MULTI_LINGUAL_EU_MODEL_FAST = "whisper-base.EUROPEAN_UNION.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_FAST = "whisper-base.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_SLOW = "whisper-small.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_MODEL_FAST = "whisper-base.tflite";
    public static final String MULTI_LINGUAL_MODEL_SLOW = "whisper-small.tflite";
    public static final String ENGLISH_ONLY_MODEL = "whisper-tiny.en.tflite";

    private File selectedTfliteFile;
    private SharedPreferences sp;
    private AutoCompleteTextView modelDropdown;
    private AutoCompleteTextView languageDropdown;
    private TextInputLayout languageLayout;
    private List<Pair<String, String>> languagePairs;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeUtils.applyNightMode(this);
        super.onCreate(savedInstanceState);
        ThemeUtils.applyPalette(this);
        setContentView(R.layout.activity_recognition_service_settings);
        ThemeUtils.setStatusBarAppearance(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        ModelDownloadManager manager = ModelDownloadManager.get(this);
        manager.ensureVocabAssets();
        if (!manager.hasAnyModel()) {
            startActivity(new Intent(this, DownloadActivity.class));
            finish();
            return;
        }

        sp = PreferenceManager.getDefaultSharedPreferences(this);
        File folder = getExternalFilesDir(null);
        ArrayList<File> files = getFilesWithExtension(folder, ".tflite");
        if (files.isEmpty()) {
            startActivity(new Intent(this, com.whispertflite.models.ModelCatalogActivity.class));
            finish();
            return;
        }

        languagePairs = LanguagePairAdapter.getLanguagePairs(this);
        List<String> languageLabels = new ArrayList<>();
        for (Pair<String, String> pair : languagePairs) languageLabels.add(pair.second);
        languageDropdown = findViewById(R.id.spnrLanguage);
        languageLayout = findViewById(R.id.layout_language);
        languageDropdown.setAdapter(menuAdapter(languageLabels));
        languageDropdown.setOnItemClickListener((parent, view, position, id) ->
                sp.edit().putString("recognitionServiceLanguage", languagePairs.get(position).first).apply());

        List<String> modelLabels = new ArrayList<>();
        for (File file : files) modelLabels.add(modelLabel(file));
        modelDropdown = findViewById(R.id.spnrTfliteFiles);
        modelDropdown.setAdapter(menuAdapter(modelLabels));

        String savedName = sp.getString("recognitionServiceModelName", MULTI_LINGUAL_TOP_WORLD_SLOW);
        selectedTfliteFile = files.get(0);
        for (File file : files) {
            if (file.getName().equals(savedName)) {
                selectedTfliteFile = file;
                break;
            }
        }
        modelDropdown.setText(modelLabel(selectedTfliteFile), false);
        applyLanguageSelection();

        modelDropdown.setOnItemClickListener((parent, view, position, id) -> {
            selectedTfliteFile = files.get(position);
            sp.edit().putString("recognitionServiceModelName", selectedTfliteFile.getName()).apply();
            applyLanguageSelection();
        });

        MaterialSwitch simpleChinese = findViewById(R.id.mode_simple_chinese);
        simpleChinese.setChecked(sp.getBoolean("RecognitionServiceSimpleChinese", false));
        simpleChinese.setOnCheckedChangeListener((button, checked) ->
                sp.edit().putBoolean("RecognitionServiceSimpleChinese", checked).apply());

        checkPermissions();
    }

    private ArrayAdapter<String> menuAdapter(List<String> values) {
        return new ArrayAdapter<>(this, R.layout.aurora_menu_item, values);
    }

    private void applyLanguageSelection() {
        boolean multilingual = isMultilingual(selectedTfliteFile);
        languageLayout.setEnabled(multilingual);
        String code = multilingual ? sp.getString("recognitionServiceLanguage", "auto") : "auto";
        int index = 0;
        for (int i = 0; i < languagePairs.size(); i++) {
            if (languagePairs.get(i).first.equals(code)) {
                index = i;
                break;
            }
        }
        languageDropdown.setText(languagePairs.get(index).second, false);
        if (!multilingual) sp.edit().putString("recognitionServiceLanguage", "auto").apply();
    }

    private boolean isMultilingual(File file) {
        String name = file.getName();
        return !name.endsWith(".en.tflite") && !name.equals(ENGLISH_ONLY_MODEL);
    }

    private String modelLabel(File file) {
        String name = file.getName();
        if (name.equals(MULTI_LINGUAL_MODEL_SLOW) || name.equals(MULTI_LINGUAL_TOP_WORLD_SLOW))
            return getString(R.string.multi_lingual_slow);
        if (name.equals(ENGLISH_ONLY_MODEL)) return getString(R.string.english_only_fast);
        if (name.equals(MULTI_LINGUAL_MODEL_FAST)
                || name.equals(MULTI_LINGUAL_EU_MODEL_FAST)
                || name.equals(MULTI_LINGUAL_TOP_WORLD_FAST))
            return getString(R.string.multi_lingual_fast);
        return name.endsWith(".tflite") ? name.substring(0, name.length() - 7) : name;
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, R.string.need_record_audio_permission, Toast.LENGTH_SHORT).show();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, granted ? "Record permission granted" : "Record permission denied");
    }

    public ArrayList<File> getFilesWithExtension(File directory, String extension) {
        ArrayList<File> result = new ArrayList<>();
        if (directory == null || !directory.exists()) return result;
        File[] files = directory.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(extension)) result.add(file);
        }
        return result;
    }
}
