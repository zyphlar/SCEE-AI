package de.westnordost.streetcomplete.voicemapper

import androidx.fragment.app.Fragment
import de.westnordost.streetcomplete.R
import org.koin.android.ext.android.inject

/**
 * Voice mapper as an overlay on the map.
 * Can be added on top of the map to provide a minimal microphone button and pending edits.
 */
class VoiceMapperOverlayFragment : Fragment(R.layout.fragment_voice_mapper) {

    private val voiceMapperService: VoiceMapperService by inject()

    companion object {
        fun newInstance() = VoiceMapperOverlayFragment()
    }
}
