package de.westnordost.streetcomplete.voicemapper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import de.westnordost.streetcomplete.data.osm.edits.ElementEditsController
import de.westnordost.streetcomplete.data.osm.edits.create.CreateNodeAction
import de.westnordost.streetcomplete.data.osm.edits.delete.DeletePoiNodeAction
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapChangesBuilder
import de.westnordost.streetcomplete.data.osm.edits.update_tags.UpdateElementTagsAction
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.*
import de.westnordost.streetcomplete.util.ktx.nowAsEpochMilliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.cos
import kotlin.math.sin

/**
 * VoiceMapperService - Handles voice input for hands-free OSM mapping
 * 
 * This service:
 * 1. Listens to spoken words using Android Speech Recognition
 * 2. Sends transcriptions to AI for parsing into OSM-compatible edits
 * 3. Creates/modifies/deletes map elements based on current position and direction
 * 
 * Example voice commands:
 * - "on the left, McDonald's, KFC, Taco Bell with addresses 123, 125, 127"
 * - "ahead on the right, gas station with Shell brand"
 * - "bench on the left, about 20 meters back"
 * - "remove the ATM marker, it doesn't exist"
 * - "the bakery is actually a restaurant now"
 */
class VoiceMapperService(
    private val context: Context
) : KoinComponent {
    
    private val elementEditsController: ElementEditsController by inject()
    private val mapDataController: MapDataController by inject()
    private val aiProcessor: VoiceMapperAIProcessor by inject()
    
    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Current location and bearing from GPS
    private var currentLocation: Location? = null
    private var currentBearing: Float = 0f
    
    // State flows for UI
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    
    private val _lastTranscription = MutableStateFlow<String?>(null)
    val lastTranscription: StateFlow<String?> = _lastTranscription
    
    private val _pendingEdits = MutableStateFlow<List<VoiceMapperEdit>>(emptyList())
    val pendingEdits: StateFlow<List<VoiceMapperEdit>> = _pendingEdits
    
    private val _events = MutableSharedFlow<VoiceMapperEvent>()
    val events: SharedFlow<VoiceMapperEvent> = _events
    
    // Configuration
    var autoConfirmEdits: Boolean = false
    var feedbackMode: FeedbackMode = FeedbackMode.VISUAL_AND_AUDIO
    
    enum class FeedbackMode {
        VISUAL_ONLY,
        AUDIO_ONLY,
        VISUAL_AND_AUDIO,
        NONE
    }
    
    fun initialize() {
        if (speechRecognizer != null) return  // already initialized
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            scope.launch {
                _events.emit(VoiceMapperEvent.Error("Speech recognition not available on this device"))
            }
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createRecognitionListener())
        }
    }
    
    fun updateLocation(location: Location, bearing: Float) {
        currentLocation = location
        currentBearing = bearing
    }
    
    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                _events.emit(VoiceMapperEvent.Error("Microphone permission required"))
            }
            return
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Enable continuous recognition for driving
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        
        speechRecognizer?.startListening(intent)
        _isListening.value = true
    }
    
    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }
    
    fun confirmPendingEdits() {
        scope.launch {
            val edits = _pendingEdits.value
            for (edit in edits) {
                applyEdit(edit)
            }
            _pendingEdits.value = emptyList()
            _events.emit(VoiceMapperEvent.EditsApplied(edits.size))
        }
    }
    
    fun cancelPendingEdits() {
        _pendingEdits.value = emptyList()
        scope.launch {
            _events.emit(VoiceMapperEvent.EditsCancelled)
        }
    }
    
    fun removeEdit(edit: VoiceMapperEdit) {
        _pendingEdits.value = _pendingEdits.value.filter { it.id != edit.id }
    }
    
    fun modifyEdit(edit: VoiceMapperEdit) {
        _pendingEdits.value = _pendingEdits.value.map {
            if (it.id == edit.id) edit else it
        }
    }

    /** Returns a stable negative node ID for use as a synthetic map element ID */
    fun nodeIdForEdit(edit: VoiceMapperEdit): Long =
        -(edit.id.hashCode().toLong().and(0x7FFFFFFF) + 1L)

    /** Looks up a pending edit by its synthetic node ID */
    fun getEditByNodeId(nodeId: Long): VoiceMapperEdit? =
        _pendingEdits.value.find { nodeIdForEdit(it) == nodeId }

    /** Confirm a single pending edit immediately to OSM without waiting for batch confirmation */
    fun confirmSingleEdit(edit: VoiceMapperEdit) {
        scope.launch {
            applyEdit(edit)
            _pendingEdits.value = _pendingEdits.value.filter { it.id != edit.id }
            _events.emit(VoiceMapperEvent.EditsApplied(1))
        }
    }
    
    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            scope.launch {
                _events.emit(VoiceMapperEvent.ReadyForSpeech)
            }
        }
        
        override fun onBeginningOfSpeech() {
            scope.launch {
                _events.emit(VoiceMapperEvent.SpeechStarted)
            }
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // Could use for visual feedback of voice level
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            scope.launch {
                _events.emit(VoiceMapperEvent.SpeechEnded)
            }
        }
        
        override fun onError(error: Int) {
            _isListening.value = false
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error: $error"
            }
            scope.launch {
                _events.emit(VoiceMapperEvent.Error(errorMessage))
            }
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcription = matches?.firstOrNull() ?: return
            
            _lastTranscription.value = transcription
            processTranscription(transcription)
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: return
            scope.launch {
                _events.emit(VoiceMapperEvent.PartialTranscription(partial))
            }
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
    
    /** Submit a text command directly, bypassing speech recognition. Uses current GPS or (0,0) if unavailable. */
    fun submitTextCommand(text: String) {
        processTranscription(text, useDummyLocationIfNeeded = true)
    }

    private fun processTranscription(transcription: String, useDummyLocationIfNeeded: Boolean = false) {
        val location = currentLocation ?: if (useDummyLocationIfNeeded) {
            android.location.Location("dummy").also { it.latitude = 0.0; it.longitude = 0.0 }
        } else {
            scope.launch { _events.emit(VoiceMapperEvent.Error("No GPS location available")) }
            return
        }
        
        scope.launch {
            _events.emit(VoiceMapperEvent.ProcessingStarted(transcription))
            
            try {
                // Get nearby map data for context
                val nearbyData = getNearbyMapData(location)
                val currentRoad = findCurrentRoad(location, nearbyData)
                
                // Create context for AI
                val context = VoiceMapperContext(
                    transcription = transcription,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    bearing = currentBearing,
                    speed = location.speed,
                    currentRoadName = currentRoad?.tags?.get("name"),
                    currentRoadRef = currentRoad?.tags?.get("ref"),
                    nearbyElements = nearbyData.toList().take(50) // Limit for context size
                )
                
                // Send to AI for processing
                val aiResponse = aiProcessor.processVoiceCommand(context)

                if (aiResponse.success) {
                    // Compute positions for each edit
                    val edits = aiResponse.edits.map { edit ->
                        if (edit.position == null) {
                            val pos = calculateRelativePosition(
                                edit.relativeSide ?: RelativeSide.RIGHT,
                                edit.distanceAhead,
                                edit.distanceSide
                            ) ?: LatLon(location.latitude, location.longitude)
                            edit.copy(position = pos)
                        } else edit
                    }

                    if (autoConfirmEdits && edits.all { it.confidence >= 0.9f }) {
                        // High confidence edits can be auto-confirmed
                        for (edit in edits) {
                            applyEdit(edit)
                        }
                        _events.emit(VoiceMapperEvent.EditsApplied(edits.size))
                    } else {
                        // Add to pending for user confirmation
                        _pendingEdits.value = _pendingEdits.value + edits
                        _events.emit(VoiceMapperEvent.EditsPending(edits))
                    }
                } else {
                    _events.emit(VoiceMapperEvent.Error(aiResponse.errorMessage ?: "Failed to process command"))
                }
            } catch (e: Exception) {
                _events.emit(VoiceMapperEvent.Error("Processing error: ${e.message}"))
            }
        }
    }
    
    private suspend fun getNearbyMapData(location: Location): MutableMapDataWithGeometry {
        // Get map data within ~100m radius
        val bbox = BoundingBox(
            location.latitude - 0.001,
            location.longitude - 0.001,
            location.latitude + 0.001,
            location.longitude + 0.001
        )
        return mapDataController.getMapDataWithGeometry(bbox)
    }
    
    private fun findCurrentRoad(location: Location, mapData: MutableMapDataWithGeometry): Way? {
        // Find the road the user is most likely on based on proximity
        val lat = location.latitude
        val lon = location.longitude
        
        return mapData.ways.filter { way ->
            val highway = way.tags["highway"]
            highway != null && highway in ROAD_TYPES
        }.minByOrNull { way ->
            // Simple distance calculation to way
            val geometry = mapData.getWayGeometry(way.id)
            (geometry as? ElementPolylinesGeometry)?.polylines?.flatten()?.minOfOrNull { point ->
                distanceBetween(lat, lon, point.latitude, point.longitude)
            } ?: Double.MAX_VALUE
        }
    }
    
    private suspend fun applyEdit(edit: VoiceMapperEdit) {
        when (edit.type) {
            EditType.CREATE_NODE -> {
                createNode(edit)
            }
            EditType.MODIFY_NODE -> {
                modifyNode(edit)
            }
            EditType.DELETE_NODE -> {
                deleteNode(edit)
            }
            EditType.MODIFY_TAGS -> {
                modifyTags(edit)
            }
        }
    }
    
    private suspend fun createNode(edit: VoiceMapperEdit) {
        val position = edit.position ?: return
        val geometry = ElementPointGeometry(position)

        elementEditsController.add(
            type = VoiceMapperQuestType,
            geometry = geometry,
            source = "VoiceMapper",
            action = CreateNodeAction(position, edit.tags),
            isNearUserLocation = true
        )
        
        _events.emit(VoiceMapperEvent.NodeCreated(edit))
    }
    
    private suspend fun modifyNode(edit: VoiceMapperEdit) {
        val elementKey = edit.elementKey ?: return
        val element = mapDataController.get(elementKey.type, elementKey.id) as? Node ?: return
        val geometry = ElementPointGeometry(element.position)
        val changes = StringMapChangesBuilder(element.tags).apply {
            edit.tags.forEach { (k, v) -> set(k, v) }
            edit.tagsToRemove.forEach { remove(it) }
        }.create()

        elementEditsController.add(
            type = VoiceMapperQuestType,
            geometry = geometry,
            source = "VoiceMapper",
            action = UpdateElementTagsAction(element, changes),
            isNearUserLocation = true
        )
        
        _events.emit(VoiceMapperEvent.NodeModified(edit))
    }
    
    private suspend fun deleteNode(edit: VoiceMapperEdit) {
        val elementKey = edit.elementKey ?: return
        val node = mapDataController.get(elementKey.type, elementKey.id) as? Node ?: return
        val geometry = ElementPointGeometry(node.position)

        elementEditsController.add(
            type = VoiceMapperQuestType,
            geometry = geometry,
            source = "VoiceMapper",
            action = DeletePoiNodeAction(node),
            isNearUserLocation = true
        )
        
        _events.emit(VoiceMapperEvent.NodeDeleted(edit))
    }
    
    private suspend fun modifyTags(edit: VoiceMapperEdit) {
        val elementKey = edit.elementKey ?: return
        val element = mapDataController.get(elementKey.type, elementKey.id) ?: return
        val geometry = mapDataController.getGeometry(elementKey.type, elementKey.id) ?: return
        val changes = StringMapChangesBuilder(element.tags).apply {
            edit.tags.forEach { (k, v) -> set(k, v) }
            edit.tagsToRemove.forEach { remove(it) }
        }.create()

        elementEditsController.add(
            type = VoiceMapperQuestType,
            geometry = geometry,
            source = "VoiceMapper",
            action = UpdateElementTagsAction(element, changes),
            isNearUserLocation = true
        )
        
        _events.emit(VoiceMapperEvent.TagsModified(edit))
    }
    
    /**
     * Calculate position relative to current location and bearing
     * @param side LEFT or RIGHT relative to direction of travel
     * @param distance Distance in meters (positive = ahead, negative = behind)
     */
    fun calculateRelativePosition(
        side: RelativeSide,
        distanceAhead: Double = 0.0,
        distanceSide: Double = 10.0
    ): LatLon? {
        val location = currentLocation ?: return null
        
        // Adjust bearing for side offset
        val sideAngle = when (side) {
            RelativeSide.LEFT -> currentBearing - 90
            RelativeSide.RIGHT -> currentBearing + 90
            RelativeSide.CENTER -> currentBearing
        }
        
        // Convert to radians
        val bearingRad = Math.toRadians(currentBearing.toDouble())
        val sideAngleRad = Math.toRadians(sideAngle.toDouble())
        
        // Calculate offset position
        // First move ahead/behind
        var lat = location.latitude
        var lon = location.longitude
        
        if (distanceAhead != 0.0) {
            val metersPerDegLat = 111320.0
            val metersPerDegLon = 111320.0 * cos(Math.toRadians(lat))
            
            lat += (distanceAhead * cos(bearingRad)) / metersPerDegLat
            lon += (distanceAhead * sin(bearingRad)) / metersPerDegLon
        }
        
        // Then move to the side
        if (distanceSide != 0.0 && side != RelativeSide.CENTER) {
            val metersPerDegLat = 111320.0
            val metersPerDegLon = 111320.0 * cos(Math.toRadians(lat))
            
            lat += (distanceSide * cos(sideAngleRad)) / metersPerDegLat
            lon += (distanceSide * sin(sideAngleRad)) / metersPerDegLon
        }
        
        return LatLon(lat, lon)
    }
    
    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }
    
    fun destroy() {
        scope.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
    
    companion object {
        private val ROAD_TYPES = setOf(
            "motorway", "trunk", "primary", "secondary", "tertiary",
            "unclassified", "residential", "service", "living_street",
            "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"
        )
    }
}

enum class RelativeSide {
    LEFT,
    RIGHT,
    CENTER
}
