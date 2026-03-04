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
        Log.d(TAG, "tryLocalParsing: \"$transcription\"")

        // Defer modification / existential commands to the AI — it handles them better
        val firstWord = transcription.split(Regex("\\s+")).firstOrNull() ?: ""
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
            // "the/this/that/those/these X is ..." pattern → likely a modification
            ((transcription.startsWith("the ") || transcription.startsWith("this ") ||
              transcription.startsWith("that ") || transcription.startsWith("those ") ||
              transcription.startsWith("these ")) &&
                transcription.contains(" is ") &&
                !transcription.contains(" is on the ") &&
                !transcription.contains(" is to the ")) ||
            // "[number] is [X]" → likely modifying an element at that address
            (firstWord.isNotEmpty() && firstWord.all { it.isDigit() } &&
                transcription.contains(" is ") &&
                !transcription.contains(" is on the ") &&
                !transcription.contains(" is to the ")) ||
            // Surface/road-property keywords → always a modification, never a creation
            SURFACE_KEYWORDS.any { transcription.contains(it) }
        if (isModification) {
            Log.d(TAG, "tryLocalParsing: deferring to AI (modification pattern detected)")
            return null
        }

        // ── Direction / side detection ─────────────────────────────────────────────
        // Compound directions take priority ("back left" = behind + to the left)
        val hasBackLeft  = transcription.contains("back left")  || transcription.contains("left back")
        val hasBackRight = transcription.contains("back right") || transcription.contains("right back")
        val hasAheadLeft  = transcription.contains("ahead left")  || transcription.contains("left ahead")
        val hasAheadRight = transcription.contains("ahead right") || transcription.contains("right ahead")
        // "back" / "behind" / "just passed" without an explicit side → center, negative distance
        val hasBack = transcription.contains(" back") || transcription.contains("behind") ||
            transcription.contains("just passed") || transcription.contains("i just passed")
        val hasFwd  = transcription.contains("ahead") || transcription.contains("in front") ||
            transcription.contains("forward") || transcription.contains("straight ahead")

        val side: RelativeSide? = when {
            hasBackLeft || hasAheadLeft -> RelativeSide.LEFT
            hasBackRight || hasAheadRight -> RelativeSide.RIGHT
            transcription.contains("on the left") || transcription.contains("to the left") ||
            transcription.contains("left side") || transcription.contains("on my left") -> RelativeSide.LEFT
            transcription.contains("on the right") || transcription.contains("to the right") ||
            transcription.contains("right side") || transcription.contains("on my right") -> RelativeSide.RIGHT
            hasFwd || hasBack -> RelativeSide.CENTER
            else -> null
        }

        // ── Distance parsing (meters, yards, feet, miles) ──────────────────────────
        // Matches: "20 yards", "100 feet", "50 m", "0.5 miles", optionally prefixed with ~about
        val distancePattern = Regex(
            """(?:about|around|roughly|approximately|~)?\s*(\d+(?:\.\d+)?)\s*(meters?|metres?|m\b|yards?|yds?\b|feet|foot|ft\b|miles?\b|mi\b)"""
        )
        val distMatches = distancePattern.findAll(transcription).toList()
        val distanceNumbersUsed = mutableSetOf<Int>()
        var computedDistanceAhead = 0.0

        if (distMatches.isNotEmpty()) {
            val m = distMatches.first()
            val value = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit  = m.groupValues[2].trimEnd()
            val meters = when {
                unit.startsWith("yard") || unit.startsWith("yd") -> value * 0.9144
                unit == "feet" || unit == "foot" || unit == "ft" -> value * 0.3048
                unit.startsWith("mile") || unit == "mi"          -> value * 1609.344
                else                                             -> value   // meters
            }
            // Negative if going backward; positive if going forward
            val isBackward = hasBack && !hasFwd
            computedDistanceAhead = if (isBackward) -meters else meters
            m.groupValues[1].toDoubleOrNull()?.toInt()?.let { distanceNumbersUsed.add(it) }
        }

        // ── Address parsing ─────────────────────────────────────────────────────────
        val addressPattern = Regex("""(?:address(?:es)?|numbers?)\s*(?:are|is)?\s*([\d,\s]+)""")
        val addressMatch = addressPattern.find(transcription)
        val explicitAddresses = addressMatch?.groupValues?.get(1)
            ?.split(Regex("[,\\s]+"))?.filter { it.isNotBlank() }
            ?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

        val addresses = if (explicitAddresses.isNotEmpty()) {
            explicitAddresses
        } else {
            Regex("""\d{1,5}""").findAll(transcription)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it !in distanceNumbersUsed }
                .toList()
        }

        // Apply default distances when a direction word is present but no explicit distance given
        if (computedDistanceAhead == 0.0) {
            when {
                hasFwd && !hasBack -> computedDistanceAhead = 25.0   // "ahead" → 25m forward
                hasBack && !hasFwd -> computedDistanceAhead = -10.0  // "back"  → 10m behind
            }
        }

        // Effective side: compound-back directions assign the side already;
        // pure "back" with no explicit side → CENTER
        val effectiveSide = side ?: if (computedDistanceAhead != 0.0) RelativeSide.CENTER else null
        Log.d(TAG, "tryLocalParsing: side=$side effectiveSide=$effectiveSide distanceAhead=$computedDistanceAhead distNums=$distanceNumbersUsed addresses=$addresses")

        // ── Part-by-part POI extraction ─────────────────────────────────────────────
        val edits = mutableListOf<VoiceMapperEdit>()
        val parts = transcription
            .replace(" and ", ",").replace(", and ", ",")
            .split(",").map { it.trim() }

        var addressIndex = 0
        for (part in parts) {
            val cleanPart = part
                // Remove compound direction phrases
                .replace(Regex("""\b(back|ahead)\s+(left|right)\b"""), "")
                .replace(Regex("""\b(left|right)\s+(back|ahead)\b"""), "")
                // Remove simple direction phrases
                .replace(Regex("""\bon the (left|right)\b"""), "")
                .replace(Regex("""\bto the (left|right)\b"""), "")
                .replace(Regex("""\b(left|right) side\b"""), "")
                .replace(Regex("""\bon my (left|right)\b"""), "")
                .replace(Regex("""\b(ahead|behind|back|forward)\b"""), "")
                // Remove address phrases
                .replace(Regex("""address(?:es)?\s*(?:are|is)?\s*[\d,\s]+"""), "")
                .replace(Regex("""with address(?:es)?.*"""), "")
                .replace(Regex("""numbers?\s*(?:are|is)?\s*[\d,\s]+"""), "")
                // Remove all distance+unit phrases (all units, with optional direction suffix)
                .replace(Regex("""(?:about|around|roughly|approximately|~)?\s*\d+(?:\.\d+)?\s*(?:meters?|metres?|m\b|yards?|yds?\b|feet|foot|ft\b|miles?\b|mi\b)[^a-z]*(?:back|behind|ahead|forward|ago|away)?"""), "")
                // Remove location-hint phrases that don't describe the POI
                .replace(Regex("""\bat the (corner|intersection|crossroads|junction|light|stop|signal)\b"""), "")
                .replace(Regex("""\bon the (corner|right side|left side)\b"""), "")
                .replace(Regex("""\bjust passed (it|it on the .+)?\b"""), "")
                // Strip leading "is [a/an] " — creation syntax like "is a restaurant"
                .replace(Regex("""^\s*is\s+(a\s+|an\s+)?"""), "")
                // Strip remaining lone digits (e.g. house numbers already handled)
                .replace(Regex("""\b\d{1,5}\b"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()

            if (cleanPart.isBlank()) {
                Log.d(TAG, "tryLocalParsing: part \"$part\" → cleanPart is blank, skipping")
                continue
            }
            Log.d(TAG, "tryLocalParsing: part \"$part\" → cleanPart \"$cleanPart\"")

            // 1. Try exact/fuzzy feature lookup (handles brands, generic amenity names)
            val (tags, extractedName) = run {
                val directTags = OSMFeatures.lookupFeature(cleanPart)
                if (directTags != null) {
                    Log.d(TAG, "tryLocalParsing: direct lookup hit: $directTags")
                    directTags to (directTags["brand"] ?: directTags["name"])
                } else {
                    // 2. Try named-business lookup ("Jimmy's pizza" → name + tags)
                    val namedResult = OSMFeatures.lookupNamedBusiness(cleanPart)
                    if (namedResult != null) {
                        Log.d(TAG, "tryLocalParsing: named-business hit: ${namedResult.first} → ${namedResult.second}")
                        namedResult.second to namedResult.first
                    } else {
                        Log.d(TAG, "tryLocalParsing: no match for \"$cleanPart\"")
                        null to null
                    }
                }
            }
            if (tags == null) continue

            val mutableTags = tags.toMutableMap()
            // Apply extracted or brand name
            if (extractedName != null && !mutableTags.containsKey("name")) {
                mutableTags["name"] = extractedName
            }

            // Assign address only when no distance phrase is present
            if (computedDistanceAhead == 0.0 && addressIndex < addresses.size) {
                mutableTags["addr:housenumber"] = addresses[addressIndex].toString()
                addressIndex++
            }
            context.currentRoadName?.let { mutableTags["addr:street"] = it }

            val label = mutableTags["name"] ?: mutableTags["brand"]
                ?: mutableTags["amenity"] ?: mutableTags["shop"] ?: "POI"
            edits.add(VoiceMapperEdit(
                type = EditType.CREATE_NODE,
                description = "Add $label",
                tags = mutableTags,
                relativeSide = effectiveSide ?: RelativeSide.RIGHT,
                distanceAhead = computedDistanceAhead,
                distanceSide = 15.0,
                confidence = if (side != null) 0.85f else 0.7f
            ))
        }

        // Address-only: numbers present but no POI type matched
        if (edits.isEmpty() && addresses.isNotEmpty() && computedDistanceAhead == 0.0) {
            for (number in addresses) {
                val tags = mutableMapOf("addr:housenumber" to number.toString())
                context.currentRoadName?.let { tags["addr:street"] = it }
                edits.add(VoiceMapperEdit(
                    type = EditType.CREATE_NODE,
                    description = "Add address $number",
                    tags = tags,
                    relativeSide = effectiveSide ?: RelativeSide.RIGHT,
                    distanceSide = 15.0,
                    confidence = if (side != null) 0.75f else 0.6f
                ))
            }
        }

        if (edits.isNotEmpty()) {
            Log.d(TAG, "tryLocalParsing: success with ${edits.size} edits")
            return VoiceMapperAIResponse(
                success = true,
                edits = edits,
                audioFeedback = "Found ${edits.size} ${if (edits.size == 1) "location" else "locations"} to add"
            )
        }
        Log.d(TAG, "tryLocalParsing: no edits produced, falling through to AI")
        return null
    }

    /**
     * Call Claude API for complex command parsing.
     * Uses tool_use to guarantee structured output — the model cannot respond with prose.
     */
    private suspend fun callClaudeAPI(context: VoiceMapperContext): VoiceMapperAIResponse {
        val requestBodyStr = buildJsonObject {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 2000)
            put("system", buildSystemPrompt())
            putJsonArray("tools") { add(buildToolDefinition()) }
            putJsonObject("tool_choice") { put("type", "any") }  // must call the tool
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", buildUserMessage(context))
                }
            }
        }.toString()

        val apiKey = de.westnordost.streetcomplete.Prefs.sharedPreferences
            .getString("anthropic_api_key", "") ?: ""
        Log.d(TAG, "callClaudeAPI: model=claude-sonnet-4-6 apiKey=${if (apiKey.isBlank()) "MISSING" else "${apiKey.take(8)}…"}")
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
You are an expert OpenStreetMap mapper assistant embedded in the SCEE app.
OUTPUT ONLY A SINGLE RAW JSON OBJECT. No explanations, no prose, no markdown, no code fences.
Your entire response must be valid JSON that can be parsed directly.

RESPONSE SCHEMA (every field required, use null/empty for unused):
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
- "[number] is [type]": "1447 is a house" or "1447 is a hairstylist" → find element with addr:housenumber=1447, update main type tags (building=house or amenity=hairstylist); if multiple matches pick closest
  Use elementSearchTags:{"addr:housenumber":"1447"}, then apply type tags
- "[number] is now [type]": same as above but explicit change
- "this/that/these/those X is Y": same as "the X is Y" — treat as modification

DIRECTIONAL MODIFICATIONS (set distanceAhead/side so the app finds the right element):
- "the building behind me" → elementSearchTags:{"building":"yes"}, distanceAhead:-10, side:CENTER
- "the crossing ahead" → elementSearchTags:{"highway":"crossing"}, distanceAhead:25, side:CENTER
- "the bus stop on the right" → elementSearchTags:{"highway":"bus_stop"}, side:RIGHT
- "the alley 200m west" (relative to bearing) → compute distanceAhead/side from cardinal direction
- ALWAYS set distanceAhead (use defaults: ahead→25, behind→-10) even for MODIFY_TAGS

SURFACE COMMANDS (always MODIFY_TAGS on a highway/path/way element, never CREATE_NODE):
- "this street is cobblestone" → look in the nearby list for the way/road the user is on (currentRoad); use elementKey if identifiable, else elementSearchTags based on road type; tags:{"surface":"cobblestone"}
- "the road surface is asphalt" → same pattern; tags:{"surface":"asphalt"}
- "this path is unpaved" → elementSearchTags:{"highway":"path"} or similar; tags:{"surface":"unpaved"}
- CRITICAL: surface commands NEVER create a new node. Always find the existing highway element.

DISTANCE/POSITION:
- "bench 20 meters back" → distanceAhead:-20, side:CENTER
- "on the left, 50m ahead" → side:LEFT, distanceAhead:50
- Default distanceSide: 15 (meters from road edge)
- UNIT CONVERSION (always output meters): 1 yard=0.9144m, 1 foot=0.3048m, 1 mile=1609.344m
- DEFAULT DISTANCES when no explicit distance given: "ahead"/"forward" → distanceAhead:25; "behind"/"back" → distanceAhead:-10
- ALWAYS set distanceAhead/side for MODIFY_TAGS with directional info — the app uses it to find the right element

ABSOLUTE CARDINAL & INTERCARDINAL DIRECTIONS (convert to relative using bearing):
- Compass bearing: N=0°, NE=45°, E=90°, SE=135°, S=180°, SW=225°, W=270°, NW=315°
- Formula: relAngle = (directionBearing - userBearing + 360) % 360
  Then: distanceAhead = distance * cos(relAngle_rad); sideComponent = distance * sin(relAngle_rad)
  if sideComponent > 0: side=RIGHT, distanceSide=sideComponent; if < 0: side=LEFT, distanceSide=|sideComponent|
  if |sideComponent| < distance*0.2: side=CENTER (roughly straight)
- Example: "200m northeast (45°)" when heading north (0°): relAngle=45°
  distanceAhead=200*cos(45°)=141, sideComponent=200*sin(45°)=141 → side:RIGHT, distanceAhead:141, distanceSide:141
- Example: "200m west (270°)" when heading east (90°): relAngle=180° → distanceAhead:-200, side:CENTER
- Example: "200m west (270°)" when heading north (0°): relAngle=270° → sideComponent=-200 → side:LEFT, distanceSide:200

NAMED BUSINESSES (extract name + type — always include name tag):
- "Jimmy's pizza on the left" → tags:{"amenity":"restaurant","cuisine":"pizza","name":"Jimmy's Pizza"}, side:LEFT
- "Bob's burgers ahead" → tags:{"amenity":"fast_food","cuisine":"burger","name":"Bob's Burgers"}, side:CENTER
- "Smith's Bakery on the right" → tags:{"shop":"bakery","name":"Smith's Bakery"}, side:RIGHT
- "Li's Chinese restaurant" → tags:{"amenity":"restaurant","cuisine":"chinese","name":"Li's Chinese Restaurant"}
- "Sal's auto repair" → tags:{"shop":"car_repair","name":"Sal's Auto Repair"}
- "The Blue Parrot bar" → tags:{"amenity":"bar","name":"The Blue Parrot"}
- Named + address: "Tony's pizza at 456 on the left" → name:"Tony's Pizza", addr:housenumber:"456", side:LEFT

COMPOUND POSITIONING EXAMPLES:
- "20 yards back left at the corner is Jimmy's pizza"
  → side:LEFT, distanceAhead:-18.3, tags:{"amenity":"restaurant","cuisine":"pizza","name":"Jimmy's Pizza"}
- "100 feet ahead on the right, Shell gas station"
  → side:RIGHT, distanceAhead:30.5, tags:{"amenity":"fuel","brand":"Shell","brand:wikidata":"Q154950","name":"Shell"}
- "50 meters back right, there's a bench"
  → side:RIGHT, distanceAhead:-50, tags:{"amenity":"bench"}
- "at the corner on the left is a CVS"
  → side:LEFT, tags:{"shop":"chemist","amenity":"pharmacy","brand":"CVS Pharmacy","brand:wikidata":"Q2078880","name":"CVS Pharmacy"}
- "just passed a fire hydrant on the left"
  → side:LEFT, distanceAhead:-5, tags:{"emergency":"fire_hydrant"}
- "McDonald's and KFC on the right, addresses 123 and 125"
  → two CREATE_NODE edits, side:RIGHT, addr:housenumber 123 and 125 respectively

INTERSECTION / LOCATION HINTS (use to describe position in explanation, not as separate tags):
- "at the corner", "at the intersection", "on the corner" → note in explanation that it's at an intersection
- "next to", "across from" → note in explanation

SENTENCE PATTERNS THAT MEAN CREATION (not modification):
- "[position] is [business]": "20 yards back left at the corner is Jimmy's pizza" → CREATE_NODE
- "there's a [business] [position]": "there's a bakery on the left" → CREATE_NODE
- "[business] [position]": "Starbucks on the right" → CREATE_NODE
- When "ahead"/"forward" present but no distance: distanceAhead:25; when "behind"/"back" present: distanceAhead:-10

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
Shelter/bus shelter "is covered"→{"covered":"yes"}, "no roof"→{"covered":"no"}, "has seating"→{"bench":"yes"}
  Search: elementSearchTags:{"amenity":"shelter"} — if no shelter node, try {"highway":"bus_stop"} with tags:{"shelter":"yes"}
ATM brand change "[OldBrand] ATM is now [NewBrand] ATM":
  elementSearchTags:{"amenity":"atm"} + elementSearchName:"OldBrand"
  tags:{"brand":"NewBrand","operator":"NewBrand","name":"NewBrand ATM"} + brand:wikidata if known
  tagsToRemove: old brand/operator/name tags only if they change

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
Respond with a single raw JSON object only. Do not write any text before or after the JSON.
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

            val contentArray = jsonResponse["content"]?.jsonArray
                ?: run {
                    Log.e(TAG, "No content array. Raw: $responseBody")
                    return VoiceMapperAIResponse(success = false, errorMessage = "No content in response")
                }

            // Primary path: tool_use block (structured — prose is impossible with tool_choice=any)
            val toolInput = contentArray
                .firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
                ?.jsonObject?.get("input")?.jsonObject

            // Fallback: parse text content as JSON (shouldn't happen with tool_choice=any)
            val parsed: JsonObject = if (toolInput != null) {
                Log.d(TAG, "parseAIResponse: tool_use path")
                toolInput
            } else {
                Log.w(TAG, "parseAIResponse: no tool_use block, attempting text fallback")
                val text = contentArray.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.content
                    ?: return VoiceMapperAIResponse(success = false, errorMessage = "No parseable content in response")
                json.parseToJsonElement(extractJson(text)).jsonObject
            }

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
                        tagsObj.entries.associate { (key, value) -> key to value.jsonPrimitive.content }
                    } ?: emptyMap()
                    val tagsToRemove = editObj["tagsToRemove"]?.jsonArray
                        ?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

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

    private fun buildToolDefinition(): JsonObject = buildJsonObject {
        put("name", "submit_osm_edits")
        put("description", "Submit structured OSM edits parsed from a voice command")
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("success") { put("type", "boolean") }
                putJsonObject("edits") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("type") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add(JsonPrimitive("CREATE_NODE"))
                                    add(JsonPrimitive("MODIFY_TAGS"))
                                    add(JsonPrimitive("DELETE_NODE"))
                                }
                            }
                            putJsonObject("description") { put("type", "string") }
                            putJsonObject("tags") {
                                put("type", "object")
                                putJsonObject("additionalProperties") { put("type", "string") }
                            }
                            putJsonObject("tagsToRemove") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                            putJsonObject("side") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add(JsonPrimitive("LEFT"))
                                    add(JsonPrimitive("RIGHT"))
                                    add(JsonPrimitive("CENTER"))
                                }
                            }
                            putJsonObject("distanceAhead") { put("type", "number") }
                            putJsonObject("distanceSide") { put("type", "number") }
                            putJsonObject("confidence") { put("type", "number") }
                            putJsonObject("explanation") { put("type", "string") }
                            putJsonObject("elementKey") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("type") { put("type", "string") }
                                    putJsonObject("id") { put("type", "integer") }
                                }
                            }
                            putJsonObject("elementSearchName") { put("type", "string") }
                            putJsonObject("elementSearchTags") {
                                put("type", "object")
                                putJsonObject("additionalProperties") { put("type", "string") }
                            }
                            putJsonObject("applyToAll") { put("type", "boolean") }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("type"))
                            add(JsonPrimitive("description"))
                        }
                    }
                }
                putJsonObject("clarificationNeeded") { put("type", "string") }
                putJsonObject("audioFeedback") { put("type", "string") }
            }
            putJsonArray("required") {
                add(JsonPrimitive("success"))
                add(JsonPrimitive("edits"))
            }
        }
    }

    /** Extract the outermost JSON object from a string, stripping any surrounding prose. */
    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        if (start == -1) return text
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                inString && c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return text.substring(start) // unclosed — return what we have
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
        // Road/path surface terms — presence of any of these strongly implies a modification
        private val SURFACE_KEYWORDS = setOf(
            "cobblestone", "asphalt", "tarmac", "concrete", "paving_stones", "paving stones",
            "unpaved", "gravel", "dirt", "grass", "sand", "sett", "compacted", "fine_gravel"
        )
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
