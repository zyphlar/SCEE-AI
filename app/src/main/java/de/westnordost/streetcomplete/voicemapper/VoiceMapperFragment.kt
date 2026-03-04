package de.westnordost.streetcomplete.voicemapper

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.databinding.FragmentVoiceMapperBinding
import de.westnordost.streetcomplete.screens.main.bottom_sheet.IsCloseableBottomSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.Locale

/**
 * Fragment for the Voice Mapper feature
 * 
 * Provides:
 * - Voice recording button with visual feedback
 * - Live transcription display  
 * - Pending edits list with confirm/reject
 * - Audio feedback via TTS
 * - Quick correction interface
 */
class VoiceMapperFragment : Fragment(), IsCloseableBottomSheet {
    
    private var _binding: FragmentVoiceMapperBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: VoiceMapperViewModel by viewModel()
    private val voiceMapperService: VoiceMapperService by inject()
    
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    
    private val pendingEditsAdapter = PendingEditsAdapter(
        onConfirm = { edit -> viewModel.confirmEdit(edit) },
        onReject = { edit -> viewModel.rejectEdit(edit) },
        onEdit = { edit -> showEditDialog(edit) }
    )
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening()
        } else {
            Toast.makeText(requireContext(), 
                "Microphone permission is required for voice mapping", 
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVoiceMapperBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize speech recognizer on main thread (required by Android)
        voiceMapperService.initialize()

        // Push layout above the keyboard when it opens
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(0, 0, 0, maxOf(imeBottom, sysBottom))
            insets
        }

        setupTTS()
        setupUI()
        observeState()
    }
    
    private fun setupTTS() {
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
    }
    
    private var pendingListVisible = false

    private fun setupUI() {
        // Voice button
        binding.voiceButton.setOnClickListener {
            toggleListening()
        }

        // Long press for continuous mode
        binding.voiceButton.setOnLongClickListener {
            viewModel.toggleContinuousMode()
            true
        }

        // Confirm all button
        binding.confirmAllButton.setOnClickListener {
            viewModel.confirmAllEdits()
        }

        // Cancel all button
        binding.cancelAllButton.setOnClickListener {
            viewModel.cancelAllEdits()
            hidePendingList()
        }

        // Settings button
        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        // Help button
        binding.helpButton.setOnClickListener {
            showHelpDialog()
        }

        // Pending count badge — toggles the full pending list
        binding.pendingCountCard.setOnClickListener {
            if (pendingListVisible) hidePendingList() else showPendingList()
        }

        // Text-input toggle button
        binding.textInputToggleButton.setOnClickListener {
            val row = binding.textInputRow
            if (row.visibility == View.VISIBLE) {
                row.visibility = View.GONE
                binding.textInputToggleButton.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            } else {
                row.visibility = View.VISIBLE
                binding.textInputToggleButton.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary))
                binding.textCommandInput.requestFocus()
            }
        }

        // Text command input
        binding.submitTextButton.setOnClickListener { submitTextCommand() }
        binding.textCommandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitTextCommand(); true
            } else false
        }

        // Pending edits list
        binding.pendingEditsList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = pendingEditsAdapter
        }
    }

    private fun showPendingList() {
        pendingListVisible = true
        binding.pendingEditsContainer.visibility = View.VISIBLE
    }

    private fun hidePendingList() {
        pendingListVisible = false
        binding.pendingEditsContainer.visibility = View.GONE
    }
    
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe listening state
                launch {
                    viewModel.isListening.collectLatest { isListening ->
                        updateVoiceButton(isListening)
                    }
                }
                
                // Observe continuous mode
                launch {
                    viewModel.isContinuousMode.collectLatest { isContinuous ->
                        binding.continuousModeIndicator.visibility = 
                            if (isContinuous) View.VISIBLE else View.GONE
                    }
                }
                
                // Observe transcription
                launch {
                    viewModel.currentTranscription.collectLatest { text ->
                        binding.transcriptionText.text = text ?: ""
                        binding.transcriptionCard.visibility = 
                            if (text.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                }
                
                // Observe pending edits — update badge; don't auto-expand the list
                launch {
                    viewModel.pendingEdits.collectLatest { edits ->
                        pendingEditsAdapter.submitList(edits)
                        binding.pendingEditsCount.text = "${edits.size} pending"
                        if (edits.isEmpty()) {
                            binding.pendingCountCard.visibility = View.GONE
                            hidePendingList()
                        } else {
                            binding.pendingCountCard.visibility = View.VISIBLE
                            binding.pendingCountBadge.text = edits.size.toString()
                        }
                    }
                }
                
                // Observe command log
                launch {
                    viewModel.commandLog.collectLatest { entries ->
                        if (entries.isEmpty()) {
                            binding.commandLogCard.visibility = View.GONE
                        } else {
                            binding.commandLogCard.visibility = View.VISIBLE
                            binding.commandLogText.text = entries.joinToString("\n")
                        }
                    }
                }

                // Observe events
                launch {
                    viewModel.events.collectLatest { event ->
                        handleEvent(event)
                    }
                }
                
                // Observe processing state
                launch {
                    viewModel.isProcessing.collectLatest { isProcessing ->
                        binding.processingIndicator.visibility = 
                            if (isProcessing) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }
    
    private fun toggleListening() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        
        viewModel.toggleListening()
    }
    
    private fun updateVoiceButton(isListening: Boolean) {
        binding.voiceButton.apply {
            if (isListening) {
                setImageResource(R.drawable.ic_mic_active)
                setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent))
                // Pulse animation
                animate()
                    .scaleX(1.2f)
                    .scaleY(1.2f)
                    .setDuration(500)
                    .withEndAction {
                        animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(500)
                            .start()
                    }
                    .start()
            } else {
                setImageResource(R.drawable.ic_mic)
                clearColorFilter()
                animate().cancel()
                scaleX = 1.0f
                scaleY = 1.0f
            }
        }
    }
    
    private fun handleEvent(event: VoiceMapperEvent) {
        when (event) {
            is VoiceMapperEvent.ReadyForSpeech -> {
                binding.statusText.text = "Listening..."
                binding.statusText.setTextColor(Color.GREEN)
            }
            
            is VoiceMapperEvent.SpeechStarted -> {
                binding.statusText.text = "Speaking..."
            }
            
            is VoiceMapperEvent.SpeechEnded -> {
                binding.statusText.text = "Processing..."
            }
            
            is VoiceMapperEvent.Error -> {
                binding.statusText.text = event.message
                binding.statusText.setTextColor(Color.RED)
                Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
            }
            
            is VoiceMapperEvent.PartialTranscription -> {
                binding.transcriptionText.text = event.text
                binding.transcriptionCard.visibility = View.VISIBLE
            }
            
            is VoiceMapperEvent.ProcessingStarted -> {
                binding.transcriptionText.text = event.transcription
            }
            
            is VoiceMapperEvent.EditsPending -> {
                speakFeedback("${event.edits.size} edits pending confirmation")
            }
            
            is VoiceMapperEvent.EditsApplied -> {
                binding.statusText.text = "Added ${event.count} items"
                binding.statusText.setTextColor(Color.GREEN)
                speakFeedback("${event.count} items added to map")
            }
            
            is VoiceMapperEvent.EditsCancelled -> {
                binding.statusText.text = "Edits cancelled"
            }
            
            is VoiceMapperEvent.NodeCreated -> {
                // Individual edit feedback handled elsewhere
            }
            
            is VoiceMapperEvent.NodeModified -> {
                // Individual edit feedback handled elsewhere
            }
            
            is VoiceMapperEvent.NodeDeleted -> {
                // Individual edit feedback handled elsewhere
            }
            
            is VoiceMapperEvent.TagsModified -> {
                // Individual edit feedback handled elsewhere
            }
            
            is VoiceMapperEvent.ClarificationNeeded -> {
                binding.statusText.text = event.question
                speakFeedback(event.question)
            }
            
            is VoiceMapperEvent.AudioFeedback -> {
                speakFeedback(event.message)
            }
        }
    }
    
    private fun speakFeedback(text: String) {
        if (!ttsReady || !viewModel.audioFeedbackEnabled.value) return
        // Pause the recognizer before speaking so TTS audio isn't fed back in
        voiceMapperService.silenceForTTS()
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { voiceMapperService.resumeFromTTS() }
            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) { voiceMapperService.resumeFromTTS() }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_mapper_feedback")
    }
    
    private fun submitTextCommand() {
        val text = binding.textCommandInput.text.toString().trim()
        if (text.isNotBlank()) {
            viewModel.submitTextCommand(text)
            binding.textCommandInput.text.clear()
        }
    }

    private fun showEditDialog(edit: VoiceMapperEdit) {
        VoiceMapperEditDialogFragment.newInstance(edit).show(
            childFragmentManager,
            "edit_dialog"
        )
    }
    
    private fun showSettingsDialog() {
        VoiceMapperSettingsDialogFragment().show(
            childFragmentManager,
            "settings_dialog"
        )
    }
    
    private fun showHelpDialog() {
        val helpText = """
            Voice Mapping Commands:

            Simple POIs:
            • "McDonald's on the left"
            • "Shell gas station on the right"
            • "KFC, Taco Bell on the left, addresses 123, 125"
            • "Bench about 20 meters back"
            • "Fire hydrant on the left, just passed it"

            Named businesses (name + type):
            • "Jimmy's pizza on the left"
            • "Bob's burgers ahead"
            • "Li's Chinese restaurant on the right"
            • "Sal's auto repair at 456 Main"

            Complex positioning:
            • "20 yards back left at the corner is Jimmy's pizza"
            • "100 feet ahead on the right, Shell station"
            • "50 meters back right, there's a bench"
            • "At the corner on the left is a CVS"

            Multiple + addresses:
            • "On the left, KFC and Taco Bell, addresses 123 and 125"
            • "McDonald's right at 200, Burger King right at 202"

            Modifications (find nearby element and update it):
            • "The bakery is now a restaurant"
            • "The Shell station is closed down"
            • "The crossing has traffic lights"
            • "That road's surface is asphalt"
            • "Speed limit is 35"
            • "The café has free wifi"
            • "The bench has no backrest"

            Deletions:
            • "Remove the ATM, it doesn't exist"
            • "The phone booth is gone"

            Tips:
            • Speak clearly at moderate pace
            • Include left/right for new POI placement
            • Yards, feet, and meters all work for distance
            • "Back left / back right / ahead left" for compound positioning
            • Long-press mic for continuous mapping mode
            • Tap the pending badge to review before confirming
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Voice Mapping Help")
            .setMessage(helpText)
            .setPositiveButton("Got it", null)
            .show()
    }
    
    // Consume all map clicks — the voice HUD should not dismiss when the user taps the map
    override fun onClickMapAt(position: LatLon, clickAreaSizeInMeters: Double): Boolean = true

    override fun onClickClose(onConfirmed: () -> Unit) {
        onConfirmed()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts?.shutdown()
        _binding = null
    }
    
    companion object {
        fun newInstance() = VoiceMapperFragment()
    }
}
