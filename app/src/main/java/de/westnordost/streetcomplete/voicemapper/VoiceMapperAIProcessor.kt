package de.westnordost.streetcomplete.voicemapper

import android.util.Log
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * AI Processor for Voice Mapper
 *
 * Uses Claude API to understand natural language voice commands and convert them
 * into structured OSM edits.
 */
class VoiceMapperAIProcessor(
    private val apiEndpoint: String = "https://api.anthropic.com/v1/messages",
    private val httpClient: HttpClient
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Process a voice command and return structured edits
     */
    suspend fun processVoiceCommand(context: VoiceMapperContext): VoiceMapperAIResponse {
        return withContext(Dispatchers.IO) {
            try {
                val localResult = tryLocalParsing(context)
                if (localResult != null && localResult.success) {
                    return@withContext localResult
                }
                callClaudeAPI(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing voice command", e)
                VoiceMapperAIResponse(
                    success = false,
                    errorMessage = "Failed to process command: ${e.message}"
                )
            }
        }
    }

    /**
     * Try to parse common patterns locally without API call
     */
    private fun tryLocalParsing(context: VoiceMapperContext): VoiceMapperAIResponse? {
        val transcription = context.transcription.lowercase()

        val side = when {
            transcription.contains("on the left") || transcription.contains("to the left") ||
            transcription.contains("left side") || transcription.contains("on my left") -> RelativeSide.LEFT
            transcription.contains("on the right") || transcription.contains("to the right") ||
            transcription.contains("right side") || transcription.contains("on my right") -> RelativeSide.RIGHT
            transcription.contains("ahead") || transcription.contains("in front") ||
            transcription.contains("straight ahead") -> RelativeSide.CENTER
            else -> null
        }

        val edits = mutableListOf<VoiceMapperEdit>()

        val parts = transcription
            .replace(" and ", ",")
            .replace(", and ", ",")
            .split(",")
            .map { it.trim() }

        val addressPattern = Regex("""(?:address(?:es)?|numbers?)\s*(?:are|is)?\s*([\d,\s]+)""")
        val addressMatch = addressPattern.find(transcription)
        val addresses = addressMatch?.groupValues?.get(1)
            ?.split(Regex("[,\\s]+"))
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()

        val numberPattern = Regex("""(\d{1,5})(?:\s*,\s*(\d{1,5}))*""")
        val numbers = if (addresses.isEmpty()) {
            numberPattern.findAll(transcription)
                .flatMap { match ->
                    match.value.split(",").mapNotNull { it.trim().toIntOrNull() }
                }
                .toList()
        } else addresses

        var addressIndex = 0

        for (part in parts) {
            val cleanPart = part
                .replace(Regex("on the (left|right)"), "")
                .replace(Regex("to the (left|right)"), "")
                .replace(Regex("(left|right) side"), "")
                .replace(Regex("on my (left|right)"), "")
                .replace(Regex("address(?:es)?\\s*(?:are|is)?\\s*[\\d,\\s]+"), "")
                .replace(Regex("with address(?:es)?.*"), "")
                .replace(Regex("numbers?\\s*(?:are|is)?\\s*[\\d,\\s]+"), "")
                .replace(Regex("\\d{1,5}"), "")
                .trim()

            if (cleanPart.isBlank()) continue

            val tags = OSMFeatures.lookupFeature(cleanPart)
            if (tags != null) {
                val mutableTags = tags.toMutableMap()

                val brand = tags["brand"]
                if (brand != null && !mutableTags.containsKey("name")) {
                    mutableTags["name"] = brand
                }

                if (addressIndex < numbers.size) {
                    mutableTags["addr:housenumber"] = numbers[addressIndex].toString()
                    addressIndex++
                }

                context.currentRoadName?.let {
                    mutableTags["addr:street"] = it
                }

                edits.add(VoiceMapperEdit(
                    type = EditType.CREATE_NODE,
                    description = "Add ${tags["brand"] ?: tags["amenity"] ?: tags["shop"] ?: "POI"}",
                    tags = mutableTags,
                    relativeSide = side ?: RelativeSide.RIGHT,
                    distanceAhead = 0.0,
                    distanceSide = 15.0,
                    confidence = if (side != null) 0.85f else 0.7f
                ))
            }
        }

        // Address-only: numbers were found but no POI type matched — create plain address node(s)
        if (edits.isEmpty() && numbers.isNotEmpty()) {
            for (number in numbers) {
                val tags = mutableMapOf("addr:housenumber" to number.toString())
                context.currentRoadName?.let { tags["addr:street"] = it }
                edits.add(VoiceMapperEdit(
                    type = EditType.CREATE_NODE,
                    description = "Add address $number",
                    tags = tags,
                    relativeSide = side ?: RelativeSide.RIGHT,
                    distanceAhead = 0.0,
                    distanceSide = 15.0,
                    confidence = if (side != null) 0.75f else 0.6f
                ))
            }
        }

        if (edits.isNotEmpty()) {
            return VoiceMapperAIResponse(
                success = true,
                edits = edits,
                audioFeedback = "Found ${edits.size} ${if (edits.size == 1) "location" else "locations"} to add"
            )
        }

        return null
    }

    /**
     * Call Claude API for complex command parsing
     */
    private suspend fun callClaudeAPI(context: VoiceMapperContext): VoiceMapperAIResponse {
        val requestBodyStr = buildJsonObject {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 2000)
            put("system", buildSystemPrompt())
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", buildUserMessage(context))
                }
            }
        }.toString()

        val apiKey = de.westnordost.streetcomplete.Prefs.sharedPreferences
            .getString("anthropic_api_key", "") ?: ""
        val response = httpClient.post(apiEndpoint) {
            headers {
                append("Content-Type", "application/json")
                append("x-api-key", apiKey)
                append("anthropic-version", "2023-06-01")
            }
            setBody(requestBodyStr)
        }

        val responseBody = response.bodyAsText()
        return parseAIResponse(responseBody, context)
    }

    private fun buildSystemPrompt(): String = """
You are an expert OpenStreetMap mapper assistant integrated into the SCEE app. Your job is to interpret voice commands from a driver/cyclist/walker who is mapping POIs while moving.

When given a voice command, output a JSON response:
{"success":true,"edits":[{"type":"CREATE_NODE|MODIFY_NODE|DELETE_NODE|MODIFY_TAGS","description":"...","tags":{},"tagsToRemove":[],"side":"LEFT|RIGHT|CENTER","distanceAhead":0,"distanceSide":15,"confidence":0.9,"explanation":"..."}],"clarificationNeeded":null,"audioFeedback":"..."}

Key rules:
1. Always include brand:wikidata for known brands
2. For fast food, always include cuisine tag
3. If multiple POIs are mentioned, create multiple edits
4. If addresses are mentioned with POIs, match them in order
5. Use the current road name for addr:street if available
6. Set confidence lower (0.5-0.7) if unsure
7. If the command is unclear, set clarificationNeeded to a question
8. Output valid JSON only, no markdown.
""".trimIndent()

    private fun buildUserMessage(context: VoiceMapperContext): String {
        val nearbyInfo = buildString {
            append("Nearby elements:\n")
            context.nearbyElements.take(20).forEach { element ->
                val name = element.tags["name"] ?: element.tags["brand"] ?: ""
                val type = element.tags["amenity"] ?: element.tags["shop"] ?: element.tags["leisure"] ?: ""
                if (name.isNotBlank() || type.isNotBlank()) {
                    append("- ${element.type}/${element.id}: $type ${if (name.isNotBlank()) "\"$name\"" else ""}\n")
                }
            }
        }

        return """
Voice command: "${context.transcription}"
Location: ${context.latitude}, ${context.longitude}, bearing ${context.bearing}°, speed ${context.speed} m/s
Road: ${context.currentRoadName ?: "Unknown"} ${context.currentRoadRef?.let { "($it)" } ?: ""}

$nearbyInfo

Parse this voice command and return the JSON response.
""".trimIndent()
    }

    private fun parseAIResponse(responseBody: String, context: VoiceMapperContext): VoiceMapperAIResponse {
        try {
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

            // Check for API-level error (e.g. auth error, rate limit)
            val errorObj = jsonResponse["error"]?.jsonObject
            if (errorObj != null) {
                val msg = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: "API error"
                val type = errorObj["type"]?.jsonPrimitive?.contentOrNull ?: ""
                Log.e(TAG, "API error ($type): $msg\nRaw: $responseBody")
                return VoiceMapperAIResponse(success = false, errorMessage = "$type: $msg")
            }

            val content = jsonResponse["content"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: run {
                    Log.e(TAG, "No content in response. Raw: $responseBody")
                    return VoiceMapperAIResponse(success = false, errorMessage = "No content in response. Raw: $responseBody")
                }

            val cleanJson = content
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            val parsed = json.parseToJsonElement(cleanJson).jsonObject

            val success = parsed["success"]?.jsonPrimitive?.boolean ?: false
            val clarificationNeeded = parsed["clarificationNeeded"]?.jsonPrimitive?.contentOrNull
            val audioFeedback = parsed["audioFeedback"]?.jsonPrimitive?.contentOrNull

            if (!success) {
                return VoiceMapperAIResponse(
                    success = false,
                    errorMessage = parsed["error"]?.jsonPrimitive?.contentOrNull ?: "AI could not process command",
                    clarificationNeeded = clarificationNeeded
                )
            }

            val editsArray = parsed["edits"]?.jsonArray ?: return VoiceMapperAIResponse(
                success = false,
                errorMessage = "No edits in response"
            )

            val edits = editsArray.mapNotNull { editJson ->
                try {
                    val editObj = editJson.jsonObject

                    val typeStr = editObj["type"]?.jsonPrimitive?.content ?: "CREATE_NODE"
                    val type = EditType.valueOf(typeStr)

                    val description = editObj["description"]?.jsonPrimitive?.content ?: "Edit"

                    val tags = editObj["tags"]?.jsonObject?.let { tagsObj ->
                        tagsObj.entries.associate { (key, value) ->
                            key to value.jsonPrimitive.content
                        }
                    } ?: emptyMap()

                    val tagsToRemove = editObj["tagsToRemove"]?.jsonArray?.map {
                        it.jsonPrimitive.content
                    }?.toSet() ?: emptySet()

                    val sideStr = editObj["side"]?.jsonPrimitive?.contentOrNull ?: "RIGHT"
                    val side = try { RelativeSide.valueOf(sideStr) } catch (e: Exception) { RelativeSide.RIGHT }

                    val distanceAhead = editObj["distanceAhead"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val distanceSide = editObj["distanceSide"]?.jsonPrimitive?.doubleOrNull ?: 15.0
                    val confidence = editObj["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.8f
                    val explanation = editObj["explanation"]?.jsonPrimitive?.contentOrNull

                    VoiceMapperEdit(
                        type = type,
                        description = description,
                        tags = tags,
                        tagsToRemove = tagsToRemove,
                        relativeSide = side,
                        distanceAhead = distanceAhead,
                        distanceSide = distanceSide,
                        confidence = confidence,
                        aiExplanation = explanation
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing edit", e)
                    null
                }
            }

            return VoiceMapperAIResponse(
                success = true,
                edits = edits,
                clarificationNeeded = clarificationNeeded,
                audioFeedback = audioFeedback
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing AI response", e)
            return VoiceMapperAIResponse(
                success = false,
                errorMessage = "Failed to parse AI response: ${e.message}"
            )
        }
    }

    fun processOffline(context: VoiceMapperContext): VoiceMapperAIResponse {
        val result = tryLocalParsing(context)
        return result ?: VoiceMapperAIResponse(
            success = false,
            errorMessage = "Could not understand command. Try: '[POI name] on the [left/right]'"
        )
    }

    companion object {
        private const val TAG = "VoiceMapperAI"
    }
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null

private val JsonPrimitive.doubleOrNull: Double?
    get() = try { double } catch (e: Exception) { null }

private val JsonPrimitive.floatOrNull: Float?
    get() = try { float } catch (e: Exception) { null }
