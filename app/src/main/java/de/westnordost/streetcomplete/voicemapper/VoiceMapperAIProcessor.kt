package de.westnordost.streetcomplete.voicemapper

import android.util.Log
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.ElementType
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
     * Try to parse simple creation-only patterns locally without an API call.
     * Returns null (falls through to AI) for anything involving modifications,
     * distance-only commands, or ambiguous input.
     */
    private fun tryLocalParsing(context: VoiceMapperContext): VoiceMapperAIResponse? {
        val transcription = context.transcription.lowercase().trim()

        // Defer modification / existential commands to the AI — it handles them better
        val isModification = transcription.contains(" is now ") ||
            transcription.contains(" are now ") ||
            transcription.contains(" was ") ||
            transcription.contains("used to be") ||
            transcription.contains("changed to") ||
            transcription.contains("doesn't exist") ||
            transcription.contains("does not exist") ||
            transcription.contains("closed down") ||
            transcription.contains("is gone") ||
            transcription.contains("no longer") ||
            transcription.contains("has closed") ||
            transcription.contains("isn't there") ||
            transcription.contains("not there") ||
            // "the X is ..." pattern without explicit side → likely a modification
            (transcription.startsWith("the ") &&
                transcription.contains(" is ") &&
                !transcription.contains(" is on the ") &&
                !transcription.contains(" is to the ") &&
                !transcription.contains(" is a ") &&
                !transcription.contains(" is an "))
        if (isModification) return null

        // Parse side
        val side = when {
            transcription.contains("on the left") || transcription.contains("to the left") ||
            transcription.contains("left side") || transcription.contains("on my left") -> RelativeSide.LEFT
            transcription.contains("on the right") || transcription.contains("to the right") ||
            transcription.contains("right side") || transcription.contains("on my right") -> RelativeSide.RIGHT
            transcription.contains("ahead") || transcription.contains("in front") ||
            transcription.contains("straight ahead") -> RelativeSide.CENTER
            else -> null
        }

        // Parse distance phrases: "about 20 meters back", "50 m ahead", "100 metres behind"
        val distanceBackPattern = Regex("""(?:about|around|roughly|~)?\s*(\d+)\s*(?:meters?|metres?|m)\b[^a-z]*(back|behind|ago)""")
        val distanceAheadPattern = Regex("""(?:about|around|roughly|~)?\s*(\d+)\s*(?:meters?|metres?|m)\b[^a-z]*(ahead|forward|in front)""")
        val distanceSidePattern = Regex("""(?:about|around|roughly|~)?\s*(\d+)\s*(?:meters?|metres?|m)\b""")

        val distBackMatch = distanceBackPattern.find(transcription)
        val distAheadMatch = distanceAheadPattern.find(transcription)
        val computedDistanceAhead: Double = when {
            distBackMatch != null -> -(distBackMatch.groupValues[1].toDoubleOrNull() ?: 0.0)
            distAheadMatch != null -> distAheadMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        // Numbers that are part of distance expressions must NOT become house numbers
        val distanceNumbersUsed = buildSet<Int> {
            distBackMatch?.groupValues?.get(1)?.toIntOrNull()?.let { add(it) }
            distAheadMatch?.groupValues?.get(1)?.toIntOrNull()?.let { add(it) }
        }

        // Explicitly named addresses ("with address 123" / "addresses 123, 125")
        val addressPattern = Regex("""(?:address(?:es)?|numbers?)\s*(?:are|is)?\s*([\d,\s]+)""")
        val addressMatch = addressPattern.find(transcription)
        val explicitAddresses = addressMatch?.groupValues?.get(1)
            ?.split(Regex("[,\\s]+"))
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()

        val numberPattern = Regex("""(\d{1,5})""")
        val addresses = if (explicitAddresses.isNotEmpty()) {
            explicitAddresses
        } else {
            // Generic numbers — exclude any used as distance values
            numberPattern.findAll(transcription)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it !in distanceNumbersUsed }
                .toList()
        }

        // Effective side: if only distance keywords (back/ahead) without explicit side, use CENTER
        val effectiveSide = side ?: if (computedDistanceAhead != 0.0) RelativeSide.CENTER else null

        val edits = mutableListOf<VoiceMapperEdit>()
        val parts = transcription
            .replace(" and ", ",")
            .replace(", and ", ",")
            .split(",")
            .map { it.trim() }

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
                // Remove distance phrases entirely
                .replace(Regex("""(?:about|around|roughly|~)?\s*\d+\s*(?:meters?|metres?|m)\b[^a-z]*(?:back|behind|ahead|forward|ago|away)?"""), "")
                .replace(Regex("\\d{1,5}"), "")
                .trim()

            if (cleanPart.isBlank()) continue

            val tags = OSMFeatures.lookupFeature(cleanPart) ?: continue
            val mutableTags = tags.toMutableMap()

            val brand = tags["brand"]
            if (brand != null && !mutableTags.containsKey("name")) {
                mutableTags["name"] = brand
            }

            // Only assign an address if it was explicitly named, or if there's no distance phrase
            if (computedDistanceAhead == 0.0 && addressIndex < addresses.size) {
                mutableTags["addr:housenumber"] = addresses[addressIndex].toString()
                addressIndex++
            }
            context.currentRoadName?.let { mutableTags["addr:street"] = it }

            edits.add(VoiceMapperEdit(
                type = EditType.CREATE_NODE,
                description = "Add ${tags["brand"] ?: tags["name"] ?: tags["amenity"] ?: tags["shop"] ?: "POI"}",
                tags = mutableTags,
                relativeSide = effectiveSide ?: RelativeSide.RIGHT,
                distanceAhead = computedDistanceAhead,
                distanceSide = 15.0,
                confidence = if (side != null) 0.85f else 0.7f
            ))
        }

        // Address-only: numbers were found but no POI type matched → create plain address node(s)
        if (edits.isEmpty() && addresses.isNotEmpty() && computedDistanceAhead == 0.0) {
            for (number in addresses) {
                val tags = mutableMapOf("addr:housenumber" to number.toString())
                context.currentRoadName?.let { tags["addr:street"] = it }
                edits.add(VoiceMapperEdit(
                    type = EditType.CREATE_NODE,
                    description = "Add address $number",
                    tags = tags,
                    relativeSide = effectiveSide ?: RelativeSide.RIGHT,
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
You are an expert OpenStreetMap mapper assistant in the SCEE app. Interpret voice commands from a moving mapper and output OSM edits as JSON.

RESPONSE FORMAT (output valid JSON only, no markdown):
{"success":true,"edits":[{"type":"CREATE_NODE|MODIFY_TAGS|DELETE_NODE","description":"...","tags":{},"tagsToRemove":[],"side":"LEFT|RIGHT|CENTER","distanceAhead":0,"distanceSide":15,"confidence":0.9,"explanation":"...","elementKey":null,"elementSearchName":null,"elementSearchTags":{},"applyToAll":false}],"clarificationNeeded":null,"audioFeedback":"..."}

EDIT TYPES:
- CREATE_NODE: Add a new POI at given side/distance from user
- MODIFY_TAGS: Update tags on an existing nearby element
- DELETE_NODE: Remove an existing nearby element

FINDING EXISTING ELEMENTS:
Priority 1 — Direct ID (best): If you can identify an element from the nearby list by its TYPE/ID, set elementKey: {"type":"NODE","id":12345}
Priority 2 — Tag search: elementSearchTags: {"shop":"bakery"} matches elements with those exact tags
Priority 3 — Name search: elementSearchName: "Smith's Bakery" for fuzzy name/brand matching
applyToAll: true = modify ALL matching elements (e.g. "paint all crosswalks")

MODIFICATION PATTERNS (always use MODIFY_TAGS, never CREATE_NODE):
- "the X is now Y" / "the X changed to Y" → find X, apply Y tags
- "the bakery is now a restaurant" → elementSearchTags:{"shop":"bakery"}, tags:{"amenity":"restaurant"}, tagsToRemove:["shop"]
- "the X is gone/closed/doesn't exist" → DELETE_NODE or add disused tags
- "the X is called Y" / "the X is named Y" → tags:{"name":"Y"}
- "the X has been demolished/removed" → DELETE_NODE

DISTANCE/POSITION:
- "bench 20 meters back" → distanceAhead:-20, side:CENTER (negative=behind user)
- "on the left, 50m ahead" → side:LEFT, distanceAhead:50
- Default distanceSide: 15 (meters from road edge)

COMMON QUEST ANSWERS (MODIFY_TAGS on nearby element):
Surface: asphalt|concrete|paving_stones|sett|cobblestone|unpaved|gravel|dirt|grass|sand|wood
  → tags:{"surface":"VALUE"}, find element by type
Max speed: "speed limit is 50" → tags:{"maxspeed":"50"} (add "mph" if mentioned)
Wheelchair: "accessible"→{"wheelchair":"yes"}, "not accessible"→{"wheelchair":"no"}, "limited"→{"wheelchair":"limited"}
Opening hours: parse carefully. Format "Mo-Fr 09:00-17:00; Sa 10:00-14:00". Use "24/7" for always open.
Vacant/closed: tags:{"disused:amenity":"ORIGINAL"}, tagsToRemove:["amenity","shop","name"]
Name correction: tags:{"name":"CORRECT NAME"}
Lit: "has lights/is lit"→{"lit":"yes"}, "no lights"→{"lit":"no"}
Lanes: "2 lanes"→{"lanes":"2"}; "3 lanes each way"→{"lanes":"6","lanes:forward":"3","lanes:backward":"3"}
Oneway: "one way"→{"oneway":"yes"}; "both ways"→{"oneway":"no"}
Cycleway: "bike lane"→{"cycleway":"lane"}, "cycle track"→{"cycleway":"track"}, "no bike lane"→{"cycleway":"no"}
Sidewalk: "sidewalk both sides"→{"sidewalk":"both"}, "left only"→{"sidewalk":"left"}, "right only"→{"sidewalk":"right"}, "none"→{"sidewalk":"none"}
Outdoor seating: {"outdoor_seating":"yes|no"}
Takeaway/delivery/drive-through: {"takeaway":"yes|only|no"}, {"delivery":"yes|no"}, {"drive_through":"yes|no"}
Diet options: {"diet:vegetarian":"yes|only"}, {"diet:vegan":"yes|only"}, {"diet:halal":"yes"}
Internet/wifi: "has wifi"→{"internet_access":"wlan","internet_access:fee":"no"}; "paid wifi"→{"internet_access":"wlan","internet_access:fee":"yes"}
Building type: {"building":"house|apartments|commercial|industrial|retail|garage|shed|yes"}
Building levels: "3 floors"→{"building:levels":"3"}
Level/floor: "second floor"→{"level":"2"}, "basement"→{"level":"-1"}, "ground floor"→{"level":"0"}
Access: "private"→{"access":"private"}, "customers only"→{"access":"customers"}, "public"→{"access":"yes"}
Smoothness: excellent|good|intermediate|bad|very_bad|horrible|very_horrible|impassable
Fuel types: {"fuel:diesel":"yes"}, {"fuel:octane_95":"yes"}, {"fuel:lpg":"yes"}, {"fuel:adblue":"yes"}
Recycling: {"recycling:glass":"yes"}, {"recycling:paper":"yes"}, {"recycling:plastic":"yes"}, {"recycling:clothes":"yes"}
Payment: {"payment:cash":"yes"}, {"payment:credit_cards":"yes"}, {"payment:contactless":"yes"}
Phone/website/operator: {"phone":"+1-555-1234"}, {"website":"https://..."}, {"operator":"Name"}
Bench backrest: {"backrest":"yes|no"}
Steps: {"step_count":"N"}, {"ramp":"yes|no"}, {"handrail":"yes|no"}
Crossing type: "zebra"→{"crossing":"zebra"}, "traffic lights"→{"crossing":"traffic_signals"}
Post box collection: {"collection_times":"Mo-Fr 09:00,17:00"}
Religion/denomination: {"religion":"christian|muslim|jewish|buddhist"}, {"denomination":"catholic|protestant|..."}

KEY RULES:
1. Include brand:wikidata for known brands in CREATE_NODE
2. Include cuisine tag for restaurants/fast food
3. Multiple POIs in one command → multiple CREATE_NODE edits
4. Match addresses to POIs in order
5. Use currentRoadName for addr:street
6. Lower confidence (0.5-0.7) when element match is uncertain
7. Set clarificationNeeded if genuinely unclear
8. For "the X is now Y": ALWAYS use MODIFY_TAGS + search, NEVER create a new node
""".trimIndent()

    private fun buildUserMessage(context: VoiceMapperContext): String {
        val nearbyInfo = buildString {
            append("Nearby elements (sorted by distance, closest first):\n")
            context.nearbyElements.take(40).forEach { element ->
                val name = element.tags["name"] ?: element.tags["brand"] ?: ""
                val mainTag = element.tags["amenity"] ?: element.tags["shop"] ?: element.tags["leisure"] ?:
                    element.tags["highway"] ?: element.tags["building"] ?: element.tags["natural"] ?: ""
                val otherTags = element.tags.entries
                    .filter { it.key !in setOf("name","brand","amenity","shop","leisure","highway","building","natural","source","created_by","source:date") }
                    .take(4)
                    .joinToString(" ") { "${it.key}=${it.value}" }
                val pos = (element as? de.westnordost.streetcomplete.data.osm.mapdata.Node)?.position
                val coordStr = if (pos != null) " @(${String.format("%.5f", pos.latitude)},${String.format("%.5f", pos.longitude)})" else ""
                if (name.isNotBlank() || mainTag.isNotBlank()) {
                    append("- ${element.type}/${element.id}:$coordStr $mainTag${if (name.isNotBlank()) " \"$name\"" else ""}${if (otherTags.isNotBlank()) " [$otherTags]" else ""}\n")
                }
            }
        }

        return """
Voice command: "${context.transcription}"
User location: ${context.latitude}, ${context.longitude}, bearing ${context.bearing}°, speed ${context.speed} m/s
Current road: ${context.currentRoadName ?: "Unknown"} ${context.currentRoadRef?.let { "($it)" } ?: ""}

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
                    val elementSearchName = editObj["elementSearchName"]?.jsonPrimitive?.contentOrNull
                    val elementSearchTags = editObj["elementSearchTags"]?.jsonObject?.let { tagsObj ->
                        tagsObj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
                    } ?: emptyMap()
                    val applyToAll = editObj["applyToAll"]?.jsonPrimitive?.boolean ?: false

                    // Direct element reference from nearby list
                    val elementKeyObj = editObj["elementKey"]?.jsonObject
                    val elementKey = if (elementKeyObj != null) {
                        try {
                            val ekType = elementKeyObj["type"]?.jsonPrimitive?.contentOrNull ?: "NODE"
                            val ekId = elementKeyObj["id"]?.jsonPrimitive?.longOrNull ?: 0L
                            if (ekId != 0L) ElementKey(ElementType.valueOf(ekType), ekId) else null
                        } catch (e: Exception) { null }
                    } else null

                    VoiceMapperEdit(
                        type = type,
                        description = description,
                        tags = tags,
                        tagsToRemove = tagsToRemove,
                        relativeSide = side,
                        distanceAhead = distanceAhead,
                        distanceSide = distanceSide,
                        confidence = confidence,
                        aiExplanation = explanation,
                        elementKey = elementKey,
                        elementSearchName = elementSearchName,
                        elementSearchTags = elementSearchTags,
                        applyToAll = applyToAll
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

private val JsonPrimitive.longOrNull: Long?
    get() = try { long } catch (e: Exception) { null }
