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
import android.util.Log
import androidx.core.content.ContextCompat
import de.westnordost.streetcomplete.data.osm.mapdata.ElementType
import org.json.JSONArray
import org.json.JSONObject
import de.westnordost.streetcomplete.data.osm.edits.ElementEditsController
import de.westnordost.streetcomplete.data.osm.edits.create.CreateNodeAction
import de.westnordost.streetcomplete.data.osm.edits.delete.DeletePoiNodeAction
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapChangesBuilder
import de.westnordost.streetcomplete.data.osm.edits.update_tags.UpdateElementTagsAction
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPolylinesGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataController
import de.westnordost.streetcomplete.data.osm.mapdata.MutableMapDataWithGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.osm.mapdata.Way
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

    // Whether the Android SpeechRecognizer is currently active (startListening called, no result/error yet)
    private var isRecognizerActive = false
    // Whether TTS is currently speaking — suppresses error-triggered restarts
    private var isTTSSpeaking = false

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

    /** Edits already submitted to OSM — kept for map-debugging purposes */
    private val _submittedEdits = MutableStateFlow<List<VoiceMapperEdit>>(emptyList())
    val submittedEdits: StateFlow<List<VoiceMapperEdit>> = _submittedEdits

    private val _events = MutableSharedFlow<VoiceMapperEvent>()
    val events: SharedFlow<VoiceMapperEvent> = _events

    private val _commandLog = MutableStateFlow<List<String>>(emptyList())
    val commandLog: StateFlow<List<String>> = _commandLog

    /** Set by VoiceMapperOverlay when a pending-edit pin is tapped; observed by VoiceMapperFragment. */
    private val _openEditRequest = MutableStateFlow<String?>(null)
    val openEditRequest: StateFlow<String?> = _openEditRequest

    fun requestOpenEdit(editId: String) { _openEditRequest.value = editId }
    fun clearOpenEditRequest() { _openEditRequest.value = null }

    private fun logCommand(entry: String) {
        _commandLog.value = (listOf(entry) + _commandLog.value).take(10)
    }

    private fun truncate(text: String, maxLen: Int = 45) =
        if (text.length <= maxLen) text else text.take(maxLen - 1) + "…"
    
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
        if (speechRecognizer != null) return  // already initialized — edits already loaded
        loadPersistedEdits()
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
    
    fun updateLocation(location: Location, gpsBearing: Float) {
        currentLocation = location
        // GPS bearing is only reliable when moving; prefer compass bearing when slow/stationary
        if (location.speed > 0.5f) currentBearing = gpsBearing
    }

    /** Update bearing from map camera rotation (compass heading in heading-up mode, degrees). */
    fun updateCompassBearing(bearing: Float) {
        currentBearing = bearing
    }
    
    fun startListening() {
        if (isRecognizerActive) {
            Log.d(TAG, "startListening: already active, ignoring")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                _events.emit(VoiceMapperEvent.Error("Microphone permission required"))
            }
            return
        }
        Log.d(TAG, "startListening: starting recognizer")
        isRecognizerActive = true
        _isListening.value = true
        speechRecognizer?.startListening(createRecognizerIntent())
    }

    fun stopListening() {
        if (isRecognizerActive) speechRecognizer?.stopListening()
        isRecognizerActive = false
        _isListening.value = false
    }

    private fun createRecognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
    }

    /** Pause the recognizer while TTS speaks — does not change the user's listening intent. */
    fun silenceForTTS() {
        Log.d(TAG, "silenceForTTS: isRecognizerActive=$isRecognizerActive")
        isTTSSpeaking = true
        if (isRecognizerActive) {
            speechRecognizer?.stopListening()
            isRecognizerActive = false
        }
    }

    /** Resume the recognizer after TTS finishes. */
    fun resumeFromTTS() {
        Log.d(TAG, "resumeFromTTS: isListening=${_isListening.value}")
        isTTSSpeaking = false
        if (_isListening.value) {
            scope.launch {
                kotlinx.coroutines.delay(200)
                if (_isListening.value && !isRecognizerActive) {
                    Log.d(TAG, "resumeFromTTS: restarting recognizer")
                    isRecognizerActive = true
                    speechRecognizer?.startListening(createRecognizerIntent())
                }
            }
        }
    }
    
    fun confirmPendingEdits() {
        scope.launch {
            val edits = _pendingEdits.value
            for (edit in edits) {
                applyEdit(edit)
            }
            _pendingEdits.value = emptyList()
            persistEdits()
            _events.emit(VoiceMapperEvent.EditsApplied(edits.size))
        }
    }

    fun cancelPendingEdits() {
        _pendingEdits.value = emptyList()
        persistEdits()
        scope.launch {
            _events.emit(VoiceMapperEvent.EditsCancelled)
        }
    }

    fun removeEdit(edit: VoiceMapperEdit) {
        _pendingEdits.value = _pendingEdits.value.filter { it.id != edit.id }
        persistEdits()
    }

    fun modifyEdit(edit: VoiceMapperEdit) {
        _pendingEdits.value = _pendingEdits.value.map {
            if (it.id == edit.id) edit else it
        }
        persistEdits()
    }

    /** Returns a stable negative node ID for use as a synthetic map element ID */
    fun nodeIdForEdit(edit: VoiceMapperEdit): Long =
        -(edit.id.hashCode().toLong().and(0x7FFFFFFF) + 1L)

    /** Looks up a pending or submitted edit by its synthetic node ID */
    fun getEditByNodeId(nodeId: Long): VoiceMapperEdit? =
        _pendingEdits.value.find { nodeIdForEdit(it) == nodeId }
            ?: _submittedEdits.value.find { nodeIdForEdit(it) == nodeId }

    fun isSubmittedEdit(nodeId: Long): Boolean =
        _submittedEdits.value.any { nodeIdForEdit(it) == nodeId }

    /** Confirm a single pending edit immediately to OSM without waiting for batch confirmation */
    fun confirmSingleEdit(edit: VoiceMapperEdit) {
        scope.launch {
            applyEdit(edit)
            _pendingEdits.value = _pendingEdits.value.filter { it.id != edit.id }
            persistEdits()
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
            isRecognizerActive = false
            Log.d(TAG, "onError: code=$error isTTSSpeaking=$isTTSSpeaking isListening=${_isListening.value}")

            // Handle fatal / special cases synchronously on main thread
            when (error) {
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _isListening.value = false
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    // Recognizer in bad state — recreate immediately (must be on main thread)
                    Log.w(TAG, "ERROR_CLIENT: recreating SpeechRecognizer")
                    speechRecognizer?.destroy()
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    speechRecognizer?.setRecognitionListener(this)
                }
            }

            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> null        // silent — normal during continuous use
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> null // silent — will auto-retry
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null  // silent — normal during continuous use
                SpeechRecognizer.ERROR_CLIENT -> null          // handled above, no toast needed
                else -> "Unknown recognizer error: $error"
            }

            val shouldRestart = _isListening.value && !isTTSSpeaking
                && error != SpeechRecognizer.ERROR_AUDIO
                && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            val delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_CLIENT) 400L else 100L

            scope.launch {
                if (errorMessage != null) _events.emit(VoiceMapperEvent.Error(errorMessage))
                if (shouldRestart) {
                    kotlinx.coroutines.delay(delayMs)
                    if (_isListening.value && !isTTSSpeaking && !isRecognizerActive) {
                        Log.d(TAG, "onError: restarting after $error")
                        isRecognizerActive = true
                        speechRecognizer?.startListening(createRecognizerIntent())
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            isRecognizerActive = false
            // Stop listening after a result — continuous mode restart is handled by the ViewModel
            _isListening.value = false
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
                // Get nearby map data for context — expand radius if a large distance is mentioned
                val mentionedDistance = extractMaxMentionedDistance(transcription)
                val nearbyData = getNearbyMapData(location, mentionedDistance)
                val currentRoad = findCurrentRoad(location, nearbyData)
                
                // Sort nearby elements by distance, closest first, for best AI context
                val sortedNearby = nearbyData.toList()
                    .filter { it.tags.isNotEmpty() }
                    .sortedBy { element ->
                        val pos = (element as? Node)?.position
                            ?: nearbyData.getGeometry(element.type, element.id)?.center
                            ?: return@sortedBy Double.MAX_VALUE
                        distanceBetween(location.latitude, location.longitude, pos.latitude, pos.longitude)
                    }
                    .take(60)

                // Create context for AI
                val context = VoiceMapperContext(
                    transcription = transcription,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    bearing = currentBearing,
                    speed = location.speed,
                    currentRoadName = currentRoad?.tags?.get("name"),
                    currentRoadRef = currentRoad?.tags?.get("ref"),
                    nearbyElements = sortedNearby
                )
                
                // Send to AI for processing
                val aiResponse = aiProcessor.processVoiceCommand(context)

                if (aiResponse.success) {
                    // Resolve element search criteria and expand applyToAll edits
                    val resolvedEdits = mutableListOf<VoiceMapperEdit>()
                    for (edit in aiResponse.edits) {
                        when {
                            edit.elementKey != null -> {
                                // AI specified exact element ID — resolve its position from nearby data
                                val matchGeom = nearbyData.getGeometry(edit.elementKey.type, edit.elementKey.id)
                                resolvedEdits.add(edit.copy(position = matchGeom?.center ?: edit.position))
                            }
                            edit.elementSearchName != null || edit.elementSearchTags.isNotEmpty() -> {
                                // Use distanceAhead/side to compute a reference search position when set
                                val searchCenter = computeSearchCenter(edit)
                                    ?: LatLon(location.latitude, location.longitude)
                                val matches = findElementsBySearch(edit, nearbyData, searchCenter)
                                if (matches.isEmpty()) {
                                    resolvedEdits.add(edit.copy(
                                        description = edit.description + " (no match found nearby)",
                                        confidence = minOf(edit.confidence, 0.5f)
                                    ))
                                } else {
                                    for (match in matches) {
                                        val matchGeom = nearbyData.getGeometry(match.type, match.id)
                                        resolvedEdits.add(edit.copy(
                                            elementKey = ElementKey(match.type, match.id),
                                            position = matchGeom?.center,
                                            description = edit.description + " (${match.tags["name"] ?: match.tags["brand"] ?: "${match.type}/${match.id}"})"
                                        ))
                                    }
                                }
                            }
                            else -> {
                                // Compute position for CREATE_NODE edits without explicit position
                                val withPosition = if (edit.position == null && edit.type == EditType.CREATE_NODE) {
                                    val pos = calculateRelativePosition(
                                        edit.relativeSide ?: RelativeSide.RIGHT,
                                        edit.distanceAhead,
                                        edit.distanceSide
                                    ) ?: LatLon(location.latitude, location.longitude)
                                    edit.copy(position = pos)
                                } else edit
                                resolvedEdits.add(withPosition)
                            }
                        }
                    }
                    // Stamp the source transcription onto every edit for traceability
                    val stamped = resolvedEdits.map {
                        if (it.sourceTranscription == null) it.copy(sourceTranscription = transcription) else it
                    }

                    if (stamped.isEmpty()) {
                        val msg = "No match for: \"${truncate(transcription)}\""
                        logCommand("✗ ${truncate(transcription)} → no match")
                        _events.emit(VoiceMapperEvent.Error(msg))
                    } else if (autoConfirmEdits && stamped.all { it.confidence >= 0.9f }) {
                        logCommand("✓ ${truncate(transcription)} → ${stamped.size} edit${if (stamped.size == 1) "" else "s"} (auto-confirmed)")
                        for (edit in stamped) { applyEdit(edit) }
                        persistEdits()
                        _events.emit(VoiceMapperEvent.EditsApplied(stamped.size))
                    } else {
                        logCommand("✓ ${truncate(transcription)} → ${stamped.size} edit${if (stamped.size == 1) "" else "s"} pending")
                        _pendingEdits.value = _pendingEdits.value + stamped
                        persistEdits()
                        _events.emit(VoiceMapperEvent.EditsPending(stamped))
                    }
                } else {
                    val errMsg = aiResponse.errorMessage ?: "Failed to process command"
                    logCommand("✗ ${truncate(transcription)} → $errMsg")
                    _events.emit(VoiceMapperEvent.Error(errMsg))
                }
            } catch (e: Exception) {
                val errMsg = "Processing error: ${e.message}"
                logCommand("✗ ${truncate(transcription)} → $errMsg")
                _events.emit(VoiceMapperEvent.Error(errMsg))
            }
        }
    }
    
    /** Extract the largest distance (in metres) mentioned in the transcription. */
    private fun extractMaxMentionedDistance(transcription: String): Double {
        val pattern = Regex(
            """(\d+(?:\.\d+)?)\s*(m\b|meters?|metres?|yards?|yds?\b|feet|foot|ft\b|km\b|miles?\b|mi\b)""",
            RegexOption.IGNORE_CASE
        )
        return pattern.findAll(transcription).maxOfOrNull { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val u = m.groupValues[2].lowercase().trimEnd()
            when {
                u.startsWith("km")                         -> v * 1000.0
                u.startsWith("mile") || u == "mi"          -> v * 1609.344
                u.startsWith("yard") || u.startsWith("yd") -> v * 0.9144
                u == "feet" || u == "foot" || u == "ft"    -> v * 0.3048
                else                                       -> v
            }
        } ?: 0.0
    }

    private suspend fun getNearbyMapData(location: Location, extraRadiusMeters: Double = 0.0): MutableMapDataWithGeometry {
        // At minimum 100m radius; expand to cover any explicitly mentioned distance + 50m buffer
        val radiusMeters = maxOf(110.0, extraRadiusMeters + 50.0)
        val radiusDeg = radiusMeters / 111_320.0
        val bbox = BoundingBox(
            location.latitude - radiusDeg,
            location.longitude - radiusDeg,
            location.latitude + radiusDeg,
            location.longitude + radiusDeg
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
    
    /**
     * Compute a geographic reference position from an edit's distanceAhead/side.
     * Returns null if no meaningful offset is specified.
     */
    private fun computeSearchCenter(edit: VoiceMapperEdit): LatLon? {
        val side = edit.relativeSide ?: RelativeSide.CENTER
        val hasAhead = edit.distanceAhead != 0.0
        // Only treat distanceSide as meaningful if it's larger than the default 15m
        val hasSide = side != RelativeSide.CENTER && edit.distanceSide > 20.0
        if (!hasAhead && !hasSide) return null
        return calculateRelativePosition(
            side,
            edit.distanceAhead,
            if (hasSide) edit.distanceSide else 0.0
        )
    }

    /**
     * Find nearby OSM elements matching the edit's search criteria.
     * Returns a list: single best match normally, or all matches when applyToAll=true.
     * [searchCenter] is the geographic point to sort by — use computed reference position
     * when the command specifies a distance/direction.
     */
    private fun findElementsBySearch(
        edit: VoiceMapperEdit,
        nearbyData: MutableMapDataWithGeometry,
        searchCenter: LatLon
    ): List<Element> {
        val lat = searchCenter.latitude
        val lon = searchCenter.longitude
        var candidates: List<Element> = nearbyData.toList()
            .filter { it.tags.isNotEmpty() }

        // Filter by required tags; value "*" means "tag must exist with any value"
        if (edit.elementSearchTags.isNotEmpty()) {
            candidates = candidates.filter { element ->
                edit.elementSearchTags.all { (k, v) ->
                    if (v == "*") element.tags.containsKey(k) else element.tags[k] == v
                }
            }
        }

        // Filter by name (fuzzy)
        if (edit.elementSearchName != null) {
            val search = edit.elementSearchName.lowercase().trim()
            candidates = candidates.filter { element ->
                val name = element.tags["name"]?.lowercase() ?: ""
                val brand = element.tags["brand"]?.lowercase() ?: ""
                name.contains(search) || brand.contains(search) ||
                    search.contains(name.takeIf { it.length > 3 } ?: return@filter false) ||
                    search.contains(brand.takeIf { it.length > 3 } ?: "NOMATCH")
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // User position for bearing-based side penalty (independent of search center)
        val userLat = currentLocation?.latitude ?: lat
        val userLon = currentLocation?.longitude ?: lon

        // Sort by distance to searchCenter; apply side/direction penalty relative to user bearing
        candidates = candidates.sortedBy { element ->
            val center = nearbyData.getGeometry(element.type, element.id)?.center
                ?: (element as? Node)?.position ?: return@sortedBy Double.MAX_VALUE
            var dist = distanceBetween(lat, lon, center.latitude, center.longitude)

            val bearing = currentBearing.toDouble()
            val elementBearing = Math.toDegrees(
                kotlin.math.atan2(
                    center.longitude - userLon,
                    center.latitude - userLat
                )
            )
            // relAngle: -180..+180; 0=directly ahead, ±180=directly behind, +90=right, -90=left
            val relAngle = ((elementBearing - bearing + 540) % 360) - 180

            // Penalize elements on the wrong left/right side
            if (edit.relativeSide != null && edit.relativeSide != RelativeSide.CENTER) {
                val wrongSide = (edit.relativeSide == RelativeSide.LEFT && relAngle > 0) ||
                    (edit.relativeSide == RelativeSide.RIGHT && relAngle < 0)
                if (wrongSide) dist += 500.0
            }

            // Penalize elements in the wrong forward/backward hemisphere for ahead/behind commands
            if ((edit.relativeSide == null || edit.relativeSide == RelativeSide.CENTER) &&
                    edit.distanceAhead != 0.0) {
                // cos(relAngle) > 0 = element is ahead, < 0 = element is behind
                val cosAngle = kotlin.math.cos(Math.toRadians(relAngle))
                val wrongDirection = (edit.distanceAhead < 0 && cosAngle > 0.1) ||
                    (edit.distanceAhead > 0 && cosAngle < -0.1)
                if (wrongDirection) dist += 300.0
            }

            dist
        }

        return if (edit.applyToAll) candidates else candidates.take(1)
    }

    private suspend fun applyEdit(edit: VoiceMapperEdit) {
        _submittedEdits.value = _submittedEdits.value + edit
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

    // ── Persistence ──────────────────────────────────────────────────────────

    private val prefs by lazy {
        context.getSharedPreferences("voice_mapper_edits", Context.MODE_PRIVATE)
    }

    private fun persistEdits() {
        try {
            val pendingArr = JSONArray().apply { _pendingEdits.value.forEach { put(it.toJson()) } }
            val submittedArr = JSONArray().apply { _submittedEdits.value.forEach { put(it.toJson()) } }
            prefs.edit()
                .putString("pending", pendingArr.toString())
                .putString("submitted", submittedArr.toString())
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist edits", e)
        }
    }

    private fun loadPersistedEdits() {
        try {
            prefs.getString("pending", null)?.let { json ->
                val arr = JSONArray(json)
                _pendingEdits.value = (0 until arr.length()).mapNotNull { arr.getJSONObject(it).toEdit() }
            }
            prefs.getString("submitted", null)?.let { json ->
                val arr = JSONArray(json)
                _submittedEdits.value = (0 until arr.length()).mapNotNull { arr.getJSONObject(it).toEdit() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted edits", e)
        }
    }

    private fun VoiceMapperEdit.toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("type", type.name)
        obj.put("description", description)
        obj.put("tags", JSONObject(tags as Map<*, *>))
        obj.put("tagsToRemove", JSONArray(tagsToRemove))
        position?.let { obj.put("lat", it.latitude); obj.put("lon", it.longitude) }
        elementKey?.let { obj.put("elType", it.type.name); obj.put("elId", it.id) }
        obj.put("confidence", confidence.toDouble())
        relativeSide?.let { obj.put("side", it.name) }
        obj.put("ahead", distanceAhead)
        obj.put("sideDist", distanceSide)
        aiExplanation?.let { obj.put("aiExp", it) }
        elementSearchName?.let { obj.put("searchName", it) }
        obj.put("searchTags", JSONObject(elementSearchTags as Map<*, *>))
        obj.put("applyAll", applyToAll)
        sourceTranscription?.let { obj.put("srcTrans", it) }
        return obj
    }

    private fun JSONObject.toEdit(): VoiceMapperEdit? = try {
        val tagsObj = optJSONObject("tags") ?: JSONObject()
        val tags = buildMap<String, String> { tagsObj.keys().forEach { put(it, tagsObj.getString(it)) } }
        val remArr = optJSONArray("tagsToRemove") ?: JSONArray()
        val tagsToRemove = buildSet<String> { for (i in 0 until remArr.length()) add(remArr.getString(i)) }
        val position = if (has("lat")) LatLon(getDouble("lat"), getDouble("lon")) else null
        val elementKey = if (has("elType")) ElementKey(
            ElementType.valueOf(getString("elType")), getLong("elId")) else null
        val searchTagsObj = optJSONObject("searchTags") ?: JSONObject()
        val searchTags = buildMap<String, String> { searchTagsObj.keys().forEach { put(it, searchTagsObj.getString(it)) } }
        VoiceMapperEdit(
            id = getString("id"),
            type = EditType.valueOf(getString("type")),
            description = getString("description"),
            tags = tags,
            tagsToRemove = tagsToRemove,
            position = position,
            elementKey = elementKey,
            confidence = getDouble("confidence").toFloat(),
            relativeSide = optString("side").takeIf { it.isNotEmpty() }?.let { RelativeSide.valueOf(it) },
            distanceAhead = getDouble("ahead"),
            distanceSide = getDouble("sideDist"),
            aiExplanation = optString("aiExp").takeIf { it.isNotEmpty() },
            elementSearchName = optString("searchName").takeIf { it.isNotEmpty() },
            elementSearchTags = searchTags,
            applyToAll = getBoolean("applyAll"),
            sourceTranscription = optString("srcTrans").takeIf { it.isNotEmpty() }
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to deserialize edit", e)
        null
    }

    companion object {
        private const val TAG = "VoiceMapperService"
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
