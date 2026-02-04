package com.gdogtak

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.gdogtak.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPreferences(this)
        applyTheme(prefs.displayTheme)
        if (prefs.displayTheme == AppPreferences.THEME_NVG) {
            setTheme(R.style.Theme_GdogTAK_NVG)
        }
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.editCallsign.setText(prefs.dogCallsign)
        binding.editUid.setText(prefs.dogUid)
        binding.editTeamName.setText(prefs.teamName)

        when (prefs.displayTheme) {
            AppPreferences.THEME_LIGHT -> binding.radioThemeLight.isChecked = true
            AppPreferences.THEME_DARK -> binding.radioThemeDark.isChecked = true
            AppPreferences.THEME_NVG -> binding.radioThemeNvg.isChecked = true
        }

        when (prefs.unitSystem) {
            AppPreferences.UNITS_IMPERIAL -> binding.radioUnitsImperial.isChecked = true
            AppPreferences.UNITS_METRIC -> binding.radioUnitsMetric.isChecked = true
        }
    }

    private fun setupListeners() {
        binding.buttonBack.setOnClickListener { finish() }

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                binding.radioThemeLight.id -> AppPreferences.THEME_LIGHT
                binding.radioThemeDark.id -> AppPreferences.THEME_DARK
                binding.radioThemeNvg.id -> AppPreferences.THEME_NVG
                else -> AppPreferences.THEME_DARK
            }
            prefs.displayTheme = theme
            applyTheme(theme)
            recreate()
        }

        binding.radioGroupUnits.setOnCheckedChangeListener { _, checkedId ->
            prefs.unitSystem = when (checkedId) {
                binding.radioUnitsImperial.id -> AppPreferences.UNITS_IMPERIAL
                binding.radioUnitsMetric.id -> AppPreferences.UNITS_METRIC
                else -> AppPreferences.UNITS_IMPERIAL
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Save text fields when leaving
        prefs.dogCallsign = binding.editCallsign.text.toString().trim().ifEmpty { "K9-DOG1" }
        prefs.dogUid = binding.editUid.text.toString().trim().ifEmpty { "GDOG-K9-001" }
        prefs.teamName = binding.editTeamName.text.toString().trim()
    }

    companion object {
        fun applyTheme(theme: String) {
            when (theme) {
                AppPreferences.THEME_LIGHT -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                AppPreferences.THEME_DARK -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
                AppPreferences.THEME_NVG -> {
                    // NVG uses dark mode as base, with custom overlay colors
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
            }
        }
    }
}
