package de.westnordost.streetcomplete.voicemapper

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for VoiceMapperFragment
 * 
 * Manages the state of voice mapping session including:
 * - Listening state
 * - Pending edits
 * - Configuration
 * - Location updates
 */
class VoiceMapperViewModel(
    private val voiceMapperService: VoiceMapperService
) : ViewModel() {
    
    // State flows
    val isListening: StateFlow<Boolean> = voiceMapperService.isListening
    val pendingEdits: StateFlow<List<VoiceMapperEdit>> = voiceMapperService.pendingEdits
    
    private val _currentTranscription = MutableStateFlow<String?>(null)
    val currentTranscription: StateFlow<String?> = _currentTranscription.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    private val _isContinuousMode = MutableStateFlow(false)
    val isContinuousMode: StateFlow<Boolean> = _isContinuousMode.asStateFlow()
    
    private val _audioFeedbackEnabled = MutableStateFlow(true)
    val audioFeedbackEnabled: StateFlow<Boolean> = _audioFeedbackEnabled.asStateFlow()
    
    private val _autoConfirmHighConfidence = MutableStateFlow(false)
    val autoConfirmHighConfidence: StateFlow<Boolean> = _autoConfirmHighConfidence.asStateFlow()
    
    // Events
    private val _events = MutableSharedFlow<VoiceMapperEvent>()
    val events: SharedFlow<VoiceMapperEvent> = _events.asSharedFlow()
    
    init {
        // Forward events from service
        viewModelScope.launch {
            voiceMapperService.events.collect { event ->
                handleServiceEvent(event)
                _events.emit(event)
            }
        }
        
        // Forward transcriptions
        viewModelScope.launch {
            voiceMapperService.lastTranscription.collect { transcription ->
                _currentTranscription.value = transcription
            }
        }
    }
    
    private fun handleServiceEvent(event: VoiceMapperEvent) {
        when (event) {
            is VoiceMapperEvent.ProcessingStarted -> {
                _isProcessing.value = true
            }
            is VoiceMapperEvent.EditsPending,
            is VoiceMapperEvent.EditsApplied,
            is VoiceMapperEvent.Error -> {
                _isProcessing.value = false
                // In continuous mode, restart listening after processing
                if (_isContinuousMode.value && event !is VoiceMapperEvent.Error) {
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(500)
                        if (_isContinuousMode.value) {
                            voiceMapperService.startListening()
                        }
                    }
                }
            }
            else -> {}
        }
    }
    
    fun submitTextCommand(text: String) {
        voiceMapperService.submitTextCommand(text)
    }

    fun toggleListening() {
        if (isListening.value) {
            voiceMapperService.stopListening()
        } else {
            voiceMapperService.startListening()
        }
    }
    
    fun startListening() {
        voiceMapperService.startListening()
    }
    
    fun stopListening() {
        voiceMapperService.stopListening()
    }
    
    fun toggleContinuousMode() {
        _isContinuousMode.value = !_isContinuousMode.value
        voiceMapperService.autoConfirmEdits = _isContinuousMode.value && _autoConfirmHighConfidence.value
        
        viewModelScope.launch {
            _events.emit(
                VoiceMapperEvent.AudioFeedback(
                    if (_isContinuousMode.value) "Continuous mode enabled" else "Continuous mode disabled"
                )
            )
        }
        
        if (_isContinuousMode.value && !isListening.value) {
            startListening()
        }
    }
    
    fun confirmEdit(edit: VoiceMapperEdit) {
        viewModelScope.launch {
            // Create a list with just this edit and confirm it
            val currentEdits = pendingEdits.value.toMutableList()
            val index = currentEdits.indexOfFirst { it.id == edit.id }
            if (index >= 0) {
                // Remove from pending and apply
                voiceMapperService.removeEdit(edit)
                // The service will apply the edit
                voiceMapperService.confirmPendingEdits()
            }
        }
    }
    
    fun rejectEdit(edit: VoiceMapperEdit) {
        voiceMapperService.removeEdit(edit)
    }
    
    fun modifyEdit(edit: VoiceMapperEdit) {
        voiceMapperService.modifyEdit(edit)
    }
    
    fun confirmAllEdits() {
        voiceMapperService.confirmPendingEdits()
    }
    
    fun cancelAllEdits() {
        voiceMapperService.cancelPendingEdits()
    }
    
    fun updateLocation(location: Location, bearing: Float) {
        voiceMapperService.updateLocation(location, bearing)
    }
    
    fun setAudioFeedback(enabled: Boolean) {
        _audioFeedbackEnabled.value = enabled
        voiceMapperService.feedbackMode = if (enabled) {
            VoiceMapperService.FeedbackMode.VISUAL_AND_AUDIO
        } else {
            VoiceMapperService.FeedbackMode.VISUAL_ONLY
        }
    }
    
    fun setAutoConfirmHighConfidence(enabled: Boolean) {
        _autoConfirmHighConfidence.value = enabled
        voiceMapperService.autoConfirmEdits = enabled && _isContinuousMode.value
    }
    
    override fun onCleared() {
        super.onCleared()
        if (_isContinuousMode.value) {
            voiceMapperService.stopListening()
        }
    }
}
