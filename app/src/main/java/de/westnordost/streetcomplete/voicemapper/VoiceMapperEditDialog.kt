package de.westnordost.streetcomplete.voicemapper

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import de.westnordost.streetcomplete.R
import org.koin.androidx.viewmodel.ext.android.getViewModel

/**
 * Settings dialog for Voice Mapper
 */
class VoiceMapperSettingsDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_voice_mapper_settings, null)

        val viewModel = getViewModel<VoiceMapperViewModel>()

        val apiKeyEdit = view.findViewById<EditText>(R.id.apiKeyEdit)
        val audioFeedbackSwitch = view.findViewById<Switch>(R.id.audioFeedbackSwitch)
        val autoConfirmSwitch = view.findViewById<Switch>(R.id.autoConfirmSwitch)
        @Suppress("UNUSED_VARIABLE")
        val defaultSideSpinner = view.findViewById<Spinner>(R.id.defaultSideSpinner)
        @Suppress("UNUSED_VARIABLE")
        val defaultDistanceEdit = view.findViewById<EditText>(R.id.defaultDistanceEdit)

        apiKeyEdit.setText(de.westnordost.streetcomplete.Prefs.sharedPreferences.getString("anthropic_api_key", "") ?: "")
        audioFeedbackSwitch.isChecked = viewModel.audioFeedbackEnabled.value
        autoConfirmSwitch.isChecked = viewModel.autoConfirmHighConfidence.value

        audioFeedbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAudioFeedback(isChecked)
        }
        autoConfirmSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoConfirmHighConfidence(isChecked)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Voice Mapper Settings")
            .setView(view)
            .setPositiveButton("Done") { _, _ ->
                val key = apiKeyEdit.text.toString().trim()
                de.westnordost.streetcomplete.Prefs.sharedPreferences.edit()
                    .putString("anthropic_api_key", key)
                    .apply()
            }
            .create()
    }
}
