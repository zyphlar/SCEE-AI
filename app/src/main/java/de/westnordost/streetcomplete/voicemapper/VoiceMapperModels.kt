package de.westnordost.streetcomplete.voicemapper

import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import java.util.UUID

/**
 * Represents a single edit to be applied to the map
 */
data class VoiceMapperEdit(
    val id: String = UUID.randomUUID().toString(),
    val type: EditType,
    val description: String,
    val tags: Map<String, String> = emptyMap(),
    val tagsToRemove: Set<String> = emptySet(),
    val position: LatLon? = null,
    val elementKey: ElementKey? = null,
    val confidence: Float = 0.0f,
    val relativeSide: RelativeSide? = null,
    val distanceAhead: Double = 0.0,
    val distanceSide: Double = 10.0,
    val aiExplanation: String? = null,
    // For modifying existing elements: search nearby map data to find the target
    val elementSearchName: String? = null,        // find by name/brand (fuzzy)
    val elementSearchTags: Map<String, String> = emptyMap(), // find by required tags (e.g. highway=crossing)
    val applyToAll: Boolean = false               // if true, apply to ALL matching elements
)

enum class EditType {
    CREATE_NODE,
    MODIFY_NODE,
    DELETE_NODE,
    MODIFY_TAGS
}

/**
 * Context provided to AI for processing voice commands
 */
data class VoiceMapperContext(
    val transcription: String,
    val latitude: Double,
    val longitude: Double,
    val bearing: Float,
    val speed: Float,
    val currentRoadName: String?,
    val currentRoadRef: String?,
    val nearbyElements: List<Element>
)

/**
 * Response from AI processing
 */
data class VoiceMapperAIResponse(
    val success: Boolean,
    val edits: List<VoiceMapperEdit> = emptyList(),
    val errorMessage: String? = null,
    val clarificationNeeded: String? = null,
    val audioFeedback: String? = null
)

/**
 * Events emitted by VoiceMapperService for UI updates
 */
sealed class VoiceMapperEvent {
    object ReadyForSpeech : VoiceMapperEvent()
    object SpeechStarted : VoiceMapperEvent()
    object SpeechEnded : VoiceMapperEvent()
    object EditsCancelled : VoiceMapperEvent()
    
    data class Error(val message: String) : VoiceMapperEvent()
    data class PartialTranscription(val text: String) : VoiceMapperEvent()
    data class ProcessingStarted(val transcription: String) : VoiceMapperEvent()
    data class EditsPending(val edits: List<VoiceMapperEdit>) : VoiceMapperEvent()
    data class EditsApplied(val count: Int) : VoiceMapperEvent()
    data class NodeCreated(val edit: VoiceMapperEdit) : VoiceMapperEvent()
    data class NodeModified(val edit: VoiceMapperEdit) : VoiceMapperEvent()
    data class NodeDeleted(val edit: VoiceMapperEdit) : VoiceMapperEvent()
    data class TagsModified(val edit: VoiceMapperEdit) : VoiceMapperEvent()
    data class ClarificationNeeded(val question: String) : VoiceMapperEvent()
    data class AudioFeedback(val message: String) : VoiceMapperEvent()
}

/**
 * OSM feature categories with common tags
 * Used by AI to correctly tag elements
 */
object OSMFeatures {
    
    val FOOD_AND_DRINK = mapOf(
        "restaurant" to mapOf("amenity" to "restaurant"),
        "fast_food" to mapOf("amenity" to "fast_food"),
        "cafe" to mapOf("amenity" to "cafe"),
        "bar" to mapOf("amenity" to "bar"),
        "pub" to mapOf("amenity" to "pub"),
        "ice_cream" to mapOf("amenity" to "ice_cream"),
        "bakery" to mapOf("shop" to "bakery"),
        "butcher" to mapOf("shop" to "butcher"),
        "deli" to mapOf("shop" to "deli"),
        "greengrocer" to mapOf("shop" to "greengrocer"),
        "supermarket" to mapOf("shop" to "supermarket"),
        "convenience" to mapOf("shop" to "convenience")
    )
    
    val FAST_FOOD_BRANDS = mapOf(
        "mcdonald's" to mapOf("amenity" to "fast_food", "brand" to "McDonald's", "brand:wikidata" to "Q38076", "cuisine" to "burger"),
        "mcdonalds" to mapOf("amenity" to "fast_food", "brand" to "McDonald's", "brand:wikidata" to "Q38076", "cuisine" to "burger"),
        "burger king" to mapOf("amenity" to "fast_food", "brand" to "Burger King", "brand:wikidata" to "Q177054", "cuisine" to "burger"),
        "wendy's" to mapOf("amenity" to "fast_food", "brand" to "Wendy's", "brand:wikidata" to "Q550258", "cuisine" to "burger"),
        "wendys" to mapOf("amenity" to "fast_food", "brand" to "Wendy's", "brand:wikidata" to "Q550258", "cuisine" to "burger"),
        "taco bell" to mapOf("amenity" to "fast_food", "brand" to "Taco Bell", "brand:wikidata" to "Q752941", "cuisine" to "mexican"),
        "kfc" to mapOf("amenity" to "fast_food", "brand" to "KFC", "brand:wikidata" to "Q524757", "cuisine" to "chicken"),
        "kentucky fried chicken" to mapOf("amenity" to "fast_food", "brand" to "KFC", "brand:wikidata" to "Q524757", "cuisine" to "chicken"),
        "subway" to mapOf("amenity" to "fast_food", "brand" to "Subway", "brand:wikidata" to "Q244457", "cuisine" to "sandwich"),
        "pizza hut" to mapOf("amenity" to "fast_food", "brand" to "Pizza Hut", "brand:wikidata" to "Q191615", "cuisine" to "pizza"),
        "domino's" to mapOf("amenity" to "fast_food", "brand" to "Domino's", "brand:wikidata" to "Q839466", "cuisine" to "pizza"),
        "dominos" to mapOf("amenity" to "fast_food", "brand" to "Domino's", "brand:wikidata" to "Q839466", "cuisine" to "pizza"),
        "chipotle" to mapOf("amenity" to "fast_food", "brand" to "Chipotle", "brand:wikidata" to "Q465751", "cuisine" to "mexican"),
        "chick-fil-a" to mapOf("amenity" to "fast_food", "brand" to "Chick-fil-A", "brand:wikidata" to "Q491516", "cuisine" to "chicken"),
        "chick fil a" to mapOf("amenity" to "fast_food", "brand" to "Chick-fil-A", "brand:wikidata" to "Q491516", "cuisine" to "chicken"),
        "popeyes" to mapOf("amenity" to "fast_food", "brand" to "Popeyes", "brand:wikidata" to "Q1330910", "cuisine" to "chicken"),
        "five guys" to mapOf("amenity" to "fast_food", "brand" to "Five Guys", "brand:wikidata" to "Q1131810", "cuisine" to "burger"),
        "in-n-out" to mapOf("amenity" to "fast_food", "brand" to "In-N-Out Burger", "brand:wikidata" to "Q1205312", "cuisine" to "burger"),
        "in n out" to mapOf("amenity" to "fast_food", "brand" to "In-N-Out Burger", "brand:wikidata" to "Q1205312", "cuisine" to "burger"),
        "sonic" to mapOf("amenity" to "fast_food", "brand" to "Sonic", "brand:wikidata" to "Q7561808", "cuisine" to "burger"),
        "arby's" to mapOf("amenity" to "fast_food", "brand" to "Arby's", "brand:wikidata" to "Q630866", "cuisine" to "sandwich"),
        "arbys" to mapOf("amenity" to "fast_food", "brand" to "Arby's", "brand:wikidata" to "Q630866", "cuisine" to "sandwich"),
        "jack in the box" to mapOf("amenity" to "fast_food", "brand" to "Jack in the Box", "brand:wikidata" to "Q1538507", "cuisine" to "burger"),
        "panda express" to mapOf("amenity" to "fast_food", "brand" to "Panda Express", "brand:wikidata" to "Q1358690", "cuisine" to "chinese"),
        "starbucks" to mapOf("amenity" to "cafe", "brand" to "Starbucks", "brand:wikidata" to "Q37158", "cuisine" to "coffee"),
        "dunkin" to mapOf("amenity" to "cafe", "brand" to "Dunkin'", "brand:wikidata" to "Q847743", "cuisine" to "coffee;donut"),
        "dunkin donuts" to mapOf("amenity" to "cafe", "brand" to "Dunkin'", "brand:wikidata" to "Q847743", "cuisine" to "coffee;donut")
    )
    
    val GAS_STATIONS = mapOf(
        "shell" to mapOf("amenity" to "fuel", "brand" to "Shell", "brand:wikidata" to "Q154950"),
        "exxon" to mapOf("amenity" to "fuel", "brand" to "Exxon", "brand:wikidata" to "Q4781"),
        "mobil" to mapOf("amenity" to "fuel", "brand" to "Mobil", "brand:wikidata" to "Q3088656"),
        "bp" to mapOf("amenity" to "fuel", "brand" to "BP", "brand:wikidata" to "Q152057"),
        "chevron" to mapOf("amenity" to "fuel", "brand" to "Chevron", "brand:wikidata" to "Q319642"),
        "texaco" to mapOf("amenity" to "fuel", "brand" to "Texaco", "brand:wikidata" to "Q775060"),
        "76" to mapOf("amenity" to "fuel", "brand" to "76", "brand:wikidata" to "Q1658320"),
        "sunoco" to mapOf("amenity" to "fuel", "brand" to "Sunoco", "brand:wikidata" to "Q1423218"),
        "circle k" to mapOf("amenity" to "fuel", "brand" to "Circle K", "brand:wikidata" to "Q3268010"),
        "speedway" to mapOf("amenity" to "fuel", "brand" to "Speedway", "brand:wikidata" to "Q7575683"),
        "wawa" to mapOf("amenity" to "fuel", "brand" to "Wawa", "brand:wikidata" to "Q5936320"),
        "sheetz" to mapOf("amenity" to "fuel", "brand" to "Sheetz", "brand:wikidata" to "Q7492551"),
        "7-eleven" to mapOf("amenity" to "fuel", "brand" to "7-Eleven", "brand:wikidata" to "Q259340"),
        "7 eleven" to mapOf("amenity" to "fuel", "brand" to "7-Eleven", "brand:wikidata" to "Q259340"),
        "costco" to mapOf("amenity" to "fuel", "brand" to "Costco Gasoline", "brand:wikidata" to "Q715583"),
        "sam's club" to mapOf("amenity" to "fuel", "brand" to "Sam's Club", "brand:wikidata" to "Q1972120"),
        "marathon" to mapOf("amenity" to "fuel", "brand" to "Marathon", "brand:wikidata" to "Q458363"),
        "valero" to mapOf("amenity" to "fuel", "brand" to "Valero", "brand:wikidata" to "Q1283291"),
        "arco" to mapOf("amenity" to "fuel", "brand" to "ARCO", "brand:wikidata" to "Q304769"),
        "phillips 66" to mapOf("amenity" to "fuel", "brand" to "Phillips 66", "brand:wikidata" to "Q1656230"),
        "conoco" to mapOf("amenity" to "fuel", "brand" to "Conoco", "brand:wikidata" to "Q1126518"),
        "quiktrip" to mapOf("amenity" to "fuel", "brand" to "QuikTrip", "brand:wikidata" to "Q7271953"),
        "racetrac" to mapOf("amenity" to "fuel", "brand" to "RaceTrac", "brand:wikidata" to "Q735942"),
        "murphy usa" to mapOf("amenity" to "fuel", "brand" to "Murphy USA", "brand:wikidata" to "Q19604459"),
        "kroger" to mapOf("amenity" to "fuel", "brand" to "Kroger", "brand:wikidata" to "Q153417"),
        "casey's" to mapOf("amenity" to "fuel", "brand" to "Casey's", "brand:wikidata" to "Q2940968"),
        "caseys" to mapOf("amenity" to "fuel", "brand" to "Casey's", "brand:wikidata" to "Q2940968"),
        "kum & go" to mapOf("amenity" to "fuel", "brand" to "Kum & Go", "brand:wikidata" to "Q6443340"),
        "pilot" to mapOf("amenity" to "fuel", "brand" to "Pilot", "brand:wikidata" to "Q64128179"),
        "flying j" to mapOf("amenity" to "fuel", "brand" to "Flying J", "brand:wikidata" to "Q64130592"),
        "loves" to mapOf("amenity" to "fuel", "brand" to "Love's", "brand:wikidata" to "Q1872496"),
        "love's" to mapOf("amenity" to "fuel", "brand" to "Love's", "brand:wikidata" to "Q1872496"),
        "ta" to mapOf("amenity" to "fuel", "brand" to "TA", "brand:wikidata" to "Q7835892"),
        "petro" to mapOf("amenity" to "fuel", "brand" to "Petro", "brand:wikidata" to "Q64051305"),
        "gas station" to mapOf("amenity" to "fuel"),
        "petrol station" to mapOf("amenity" to "fuel"),
        "fuel station" to mapOf("amenity" to "fuel")
    )
    
    val RETAIL = mapOf(
        "walmart" to mapOf("shop" to "supermarket", "brand" to "Walmart", "brand:wikidata" to "Q483551"),
        "target" to mapOf("shop" to "department_store", "brand" to "Target", "brand:wikidata" to "Q1046951"),
        "costco" to mapOf("shop" to "wholesale", "brand" to "Costco", "brand:wikidata" to "Q715583"),
        "sam's club" to mapOf("shop" to "wholesale", "brand" to "Sam's Club", "brand:wikidata" to "Q1972120"),
        "kroger" to mapOf("shop" to "supermarket", "brand" to "Kroger", "brand:wikidata" to "Q153417"),
        "safeway" to mapOf("shop" to "supermarket", "brand" to "Safeway", "brand:wikidata" to "Q1508234"),
        "albertsons" to mapOf("shop" to "supermarket", "brand" to "Albertsons", "brand:wikidata" to "Q4712282"),
        "publix" to mapOf("shop" to "supermarket", "brand" to "Publix", "brand:wikidata" to "Q672170"),
        "aldi" to mapOf("shop" to "supermarket", "brand" to "ALDI", "brand:wikidata" to "Q125054"),
        "lidl" to mapOf("shop" to "supermarket", "brand" to "Lidl", "brand:wikidata" to "Q151954"),
        "trader joe's" to mapOf("shop" to "supermarket", "brand" to "Trader Joe's", "brand:wikidata" to "Q688825"),
        "whole foods" to mapOf("shop" to "supermarket", "brand" to "Whole Foods Market", "brand:wikidata" to "Q1809448"),
        "cvs" to mapOf("shop" to "chemist", "amenity" to "pharmacy", "brand" to "CVS Pharmacy", "brand:wikidata" to "Q2078880"),
        "walgreens" to mapOf("shop" to "chemist", "amenity" to "pharmacy", "brand" to "Walgreens", "brand:wikidata" to "Q1591889"),
        "rite aid" to mapOf("shop" to "chemist", "amenity" to "pharmacy", "brand" to "Rite Aid", "brand:wikidata" to "Q3433273"),
        "home depot" to mapOf("shop" to "doityourself", "brand" to "The Home Depot", "brand:wikidata" to "Q864407"),
        "lowe's" to mapOf("shop" to "doityourself", "brand" to "Lowe's", "brand:wikidata" to "Q1373493"),
        "lowes" to mapOf("shop" to "doityourself", "brand" to "Lowe's", "brand:wikidata" to "Q1373493"),
        "best buy" to mapOf("shop" to "electronics", "brand" to "Best Buy", "brand:wikidata" to "Q533415"),
        "autozone" to mapOf("shop" to "car_parts", "brand" to "AutoZone", "brand:wikidata" to "Q4826087"),
        "o'reilly" to mapOf("shop" to "car_parts", "brand" to "O'Reilly Auto Parts", "brand:wikidata" to "Q7071951"),
        "advance auto parts" to mapOf("shop" to "car_parts", "brand" to "Advance Auto Parts", "brand:wikidata" to "Q4686051"),
        "dollar general" to mapOf("shop" to "variety_store", "brand" to "Dollar General", "brand:wikidata" to "Q145168"),
        "dollar tree" to mapOf("shop" to "variety_store", "brand" to "Dollar Tree", "brand:wikidata" to "Q5289230"),
        "family dollar" to mapOf("shop" to "variety_store", "brand" to "Family Dollar", "brand:wikidata" to "Q5433101")
    )
    
    val BANKS = mapOf(
        "chase" to mapOf("amenity" to "bank", "brand" to "Chase", "brand:wikidata" to "Q524629"),
        "bank of america" to mapOf("amenity" to "bank", "brand" to "Bank of America", "brand:wikidata" to "Q487907"),
        "wells fargo" to mapOf("amenity" to "bank", "brand" to "Wells Fargo", "brand:wikidata" to "Q744149"),
        "citibank" to mapOf("amenity" to "bank", "brand" to "Citibank", "brand:wikidata" to "Q857063"),
        "us bank" to mapOf("amenity" to "bank", "brand" to "U.S. Bank", "brand:wikidata" to "Q739084"),
        "pnc" to mapOf("amenity" to "bank", "brand" to "PNC Bank", "brand:wikidata" to "Q38928"),
        "td bank" to mapOf("amenity" to "bank", "brand" to "TD Bank", "brand:wikidata" to "Q7669891"),
        "capital one" to mapOf("amenity" to "bank", "brand" to "Capital One", "brand:wikidata" to "Q1034654"),
        "fifth third" to mapOf("amenity" to "bank", "brand" to "Fifth Third Bank", "brand:wikidata" to "Q1411810"),
        "regions" to mapOf("amenity" to "bank", "brand" to "Regions Bank", "brand:wikidata" to "Q917131"),
        "suntrust" to mapOf("amenity" to "bank", "brand" to "SunTrust", "brand:wikidata" to "Q181507"),
        "bb&t" to mapOf("amenity" to "bank", "brand" to "BB&T", "brand:wikidata" to "Q795486"),
        "truist" to mapOf("amenity" to "bank", "brand" to "Truist", "brand:wikidata" to "Q100891638"),
        "huntington" to mapOf("amenity" to "bank", "brand" to "Huntington Bank", "brand:wikidata" to "Q798819"),
        "atm" to mapOf("amenity" to "atm"),
        "cash machine" to mapOf("amenity" to "atm")
    )
    
    val AMENITIES = mapOf(
        "bench" to mapOf("amenity" to "bench"),
        "picnic table" to mapOf("leisure" to "picnic_table"),
        "picnic bench" to mapOf("leisure" to "picnic_table"),
        "trash can" to mapOf("amenity" to "waste_basket"),
        "trash bin" to mapOf("amenity" to "waste_basket"),
        "rubbish bin" to mapOf("amenity" to "waste_basket"),
        "waste basket" to mapOf("amenity" to "waste_basket"),
        "litter bin" to mapOf("amenity" to "waste_basket"),
        "recycling" to mapOf("amenity" to "recycling"),
        "recycling bin" to mapOf("amenity" to "recycling"),
        "drinking fountain" to mapOf("amenity" to "drinking_water"),
        "water fountain" to mapOf("amenity" to "drinking_water"),
        "water tap" to mapOf("amenity" to "drinking_water"),
        "toilet" to mapOf("amenity" to "toilets"),
        "toilets" to mapOf("amenity" to "toilets"),
        "restroom" to mapOf("amenity" to "toilets"),
        "bathroom" to mapOf("amenity" to "toilets"),
        "public toilet" to mapOf("amenity" to "toilets"),
        "parking" to mapOf("amenity" to "parking"),
        "car park" to mapOf("amenity" to "parking"),
        "parking lot" to mapOf("amenity" to "parking"),
        "bicycle parking" to mapOf("amenity" to "bicycle_parking"),
        "bike rack" to mapOf("amenity" to "bicycle_parking"),
        "bike parking" to mapOf("amenity" to "bicycle_parking"),
        "post box" to mapOf("amenity" to "post_box"),
        "mailbox" to mapOf("amenity" to "post_box"),
        "letter box" to mapOf("amenity" to "post_box"),
        "post office" to mapOf("amenity" to "post_office"),
        "telephone" to mapOf("amenity" to "telephone"),
        "phone booth" to mapOf("amenity" to "telephone"),
        "phone box" to mapOf("amenity" to "telephone"),
        "bus stop" to mapOf("highway" to "bus_stop", "public_transport" to "platform"),
        "tram stop" to mapOf("railway" to "tram_stop", "public_transport" to "stop_position"),
        "fire hydrant" to mapOf("emergency" to "fire_hydrant"),
        "hydrant" to mapOf("emergency" to "fire_hydrant"),
        "fire station" to mapOf("amenity" to "fire_station"),
        "police station" to mapOf("amenity" to "police"),
        "street light" to mapOf("highway" to "street_lamp"),
        "street lamp" to mapOf("highway" to "street_lamp"),
        "lamp post" to mapOf("highway" to "street_lamp"),
        "light pole" to mapOf("highway" to "street_lamp"),
        "bollard" to mapOf("barrier" to "bollard"),
        "tree" to mapOf("natural" to "tree"),
        "speed limit sign" to mapOf("traffic_sign" to "maxspeed"),
        "stop sign" to mapOf("highway" to "stop"),
        "crosswalk" to mapOf("highway" to "crossing"),
        "crossing" to mapOf("highway" to "crossing"),
        "pedestrian crossing" to mapOf("highway" to "crossing"),
        "traffic signal" to mapOf("highway" to "traffic_signals"),
        "traffic light" to mapOf("highway" to "traffic_signals"),
        "traffic signals" to mapOf("highway" to "traffic_signals"),
        "manhole" to mapOf("man_made" to "manhole"),
        "drain" to mapOf("man_made" to "drain"),
        "gully" to mapOf("man_made" to "drain"),
        "survey point" to mapOf("man_made" to "survey_point"),
        "milestone" to mapOf("historic" to "milestone"),
        "information board" to mapOf("tourism" to "information", "information" to "board"),
        "information sign" to mapOf("tourism" to "information", "information" to "sign"),
        "map" to mapOf("tourism" to "information", "information" to "map"),
        "vending machine" to mapOf("amenity" to "vending_machine"),
        "ticket machine" to mapOf("amenity" to "vending_machine", "vending" to "public_transport_tickets"),
        "playground" to mapOf("leisure" to "playground"),
        "park" to mapOf("leisure" to "park"),
        "shelter" to mapOf("amenity" to "shelter"),
        "bus shelter" to mapOf("amenity" to "shelter", "shelter_type" to "public_transport"),
        "charging station" to mapOf("amenity" to "charging_station"),
        "ev charger" to mapOf("amenity" to "charging_station"),
        "electric vehicle charger" to mapOf("amenity" to "charging_station"),
        "bicycle rental" to mapOf("amenity" to "bicycle_rental"),
        "bike share" to mapOf("amenity" to "bicycle_rental"),
        "defibrillator" to mapOf("emergency" to "defibrillator"),
        "aed" to mapOf("emergency" to "defibrillator")
    )

    val INFRASTRUCTURE = mapOf(
        "power pole" to mapOf("power" to "pole"),
        "utility pole" to mapOf("power" to "pole"),
        "electricity pole" to mapOf("power" to "pole"),
        "telephone pole" to mapOf("power" to "pole"),
        "wooden pole" to mapOf("power" to "pole"),
        "power tower" to mapOf("power" to "tower"),
        "electricity pylon" to mapOf("power" to "tower"),
        "pylon" to mapOf("power" to "tower"),
        "transmission tower" to mapOf("power" to "tower"),
        "power substation" to mapOf("power" to "substation"),
        "substation" to mapOf("power" to "substation"),
        "transformer" to mapOf("power" to "transformer"),
        "water tower" to mapOf("man_made" to "water_tower"),
        "water pump" to mapOf("man_made" to "water_well"),
        "well" to mapOf("man_made" to "water_well"),
        "chimney" to mapOf("man_made" to "chimney"),
        "antenna" to mapOf("man_made" to "antenna"),
        "mast" to mapOf("man_made" to "mast"),
        "mobile phone mast" to mapOf("man_made" to "mast", "tower:type" to "communication"),
        "cell tower" to mapOf("man_made" to "mast", "tower:type" to "communication"),
        "telecom mast" to mapOf("man_made" to "mast", "tower:type" to "communication"),
        "pipeline" to mapOf("man_made" to "pipeline"),
        "gate" to mapOf("barrier" to "gate"),
        "fence" to mapOf("barrier" to "fence"),
        "wall" to mapOf("barrier" to "wall"),
        "speed bump" to mapOf("traffic_calming" to "bump"),
        "speed hump" to mapOf("traffic_calming" to "hump"),
        "speed table" to mapOf("traffic_calming" to "table"),
        "rumble strip" to mapOf("traffic_calming" to "rumble_strip"),
        "tank" to mapOf("man_made" to "storage_tank"),
        "storage tank" to mapOf("man_made" to "storage_tank"),
        "silo" to mapOf("man_made" to "silo"),
        "flagpole" to mapOf("man_made" to "flagpole")
    )

    val OFFICES = mapOf(
        "office" to mapOf("office" to "yes"),
        "company office" to mapOf("office" to "company"),
        "company" to mapOf("office" to "company"),
        "business" to mapOf("office" to "company"),
        "government office" to mapOf("office" to "government"),
        "government" to mapOf("office" to "government"),
        "council office" to mapOf("office" to "government"),
        "law office" to mapOf("office" to "lawyer"),
        "lawyer" to mapOf("office" to "lawyer"),
        "solicitor" to mapOf("office" to "lawyer"),
        "estate agent" to mapOf("office" to "estate_agent"),
        "real estate" to mapOf("office" to "estate_agent"),
        "accountant" to mapOf("office" to "accountant"),
        "insurance" to mapOf("office" to "insurance"),
        "travel agent" to mapOf("office" to "travel_agent"),
        "it company" to mapOf("office" to "it"),
        "ngo" to mapOf("office" to "ngo"),
        "charity" to mapOf("office" to "ngo"),
        "educational" to mapOf("office" to "educational_institution"),
        "research" to mapOf("office" to "research"),
        "architect" to mapOf("office" to "architect"),
        "financial" to mapOf("office" to "financial"),
        "newspaper" to mapOf("office" to "newspaper"),
        "political party" to mapOf("office" to "political_party"),
        "religion" to mapOf("office" to "religion"),
        "doctor" to mapOf("amenity" to "doctors"),
        "doctors" to mapOf("amenity" to "doctors"),
        "dentist" to mapOf("amenity" to "dentist"),
        "clinic" to mapOf("amenity" to "clinic"),
        "hospital" to mapOf("amenity" to "hospital"),
        "pharmacy" to mapOf("amenity" to "pharmacy"),
        "chemist" to mapOf("shop" to "chemist"),
        "veterinary" to mapOf("amenity" to "veterinary"),
        "vet" to mapOf("amenity" to "veterinary"),
        "school" to mapOf("amenity" to "school"),
        "university" to mapOf("amenity" to "university"),
        "college" to mapOf("amenity" to "college"),
        "kindergarten" to mapOf("amenity" to "kindergarten"),
        "nursery" to mapOf("amenity" to "kindergarten"),
        "library" to mapOf("amenity" to "library"),
        "community centre" to mapOf("amenity" to "community_centre"),
        "community center" to mapOf("amenity" to "community_centre"),
        "sports centre" to mapOf("leisure" to "sports_centre"),
        "sports center" to mapOf("leisure" to "sports_centre"),
        "gym" to mapOf("leisure" to "fitness_centre"),
        "fitness center" to mapOf("leisure" to "fitness_centre"),
        "fitness centre" to mapOf("leisure" to "fitness_centre"),
        "swimming pool" to mapOf("leisure" to "swimming_pool"),
        "cinema" to mapOf("amenity" to "cinema"),
        "movie theater" to mapOf("amenity" to "cinema"),
        "theatre" to mapOf("amenity" to "theatre"),
        "theater" to mapOf("amenity" to "theatre"),
        "place of worship" to mapOf("amenity" to "place_of_worship"),
        "church" to mapOf("amenity" to "place_of_worship", "religion" to "christian"),
        "mosque" to mapOf("amenity" to "place_of_worship", "religion" to "muslim"),
        "synagogue" to mapOf("amenity" to "place_of_worship", "religion" to "jewish"),
        "temple" to mapOf("amenity" to "place_of_worship"),
        "car wash" to mapOf("amenity" to "car_wash"),
        "car dealership" to mapOf("shop" to "car"),
        "car repair" to mapOf("shop" to "car_repair"),
        "garage" to mapOf("shop" to "car_repair"),
        "mechanic" to mapOf("shop" to "car_repair"),
        "hairdresser" to mapOf("shop" to "hairdresser"),
        "barber" to mapOf("shop" to "barber"),
        "beauty salon" to mapOf("shop" to "beauty"),
        "nail salon" to mapOf("shop" to "beauty"),
        "laundry" to mapOf("shop" to "laundry"),
        "dry cleaner" to mapOf("shop" to "dry_cleaning"),
        "shoe shop" to mapOf("shop" to "shoes"),
        "clothes shop" to mapOf("shop" to "clothes"),
        "clothing store" to mapOf("shop" to "clothes"),
        "furniture store" to mapOf("shop" to "furniture"),
        "bookshop" to mapOf("shop" to "books"),
        "bookstore" to mapOf("shop" to "books"),
        "florist" to mapOf("shop" to "florist"),
        "garden centre" to mapOf("shop" to "garden_centre"),
        "garden center" to mapOf("shop" to "garden_centre"),
        "sports shop" to mapOf("shop" to "sports"),
        "toy shop" to mapOf("shop" to "toys"),
        "pet shop" to mapOf("shop" to "pet"),
        "jeweller" to mapOf("shop" to "jewelry"),
        "jewelry store" to mapOf("shop" to "jewelry"),
        "optician" to mapOf("shop" to "optician"),
        "mobile phone shop" to mapOf("shop" to "mobile_phone"),
        "phone shop" to mapOf("shop" to "mobile_phone"),
        "hardware store" to mapOf("shop" to "hardware"),
        "department store" to mapOf("shop" to "department_store"),
        "shopping mall" to mapOf("shop" to "mall"),
        "mall" to mapOf("shop" to "mall"),
        "hostel" to mapOf("tourism" to "hostel"),
        "campsite" to mapOf("tourism" to "camp_site"),
        "camp site" to mapOf("tourism" to "camp_site"),
        "caravan site" to mapOf("tourism" to "caravan_site"),
        "rv park" to mapOf("tourism" to "caravan_site"),
        "viewpoint" to mapOf("tourism" to "viewpoint"),
        "museum" to mapOf("tourism" to "museum"),
        "gallery" to mapOf("tourism" to "gallery"),
        "art gallery" to mapOf("tourism" to "gallery"),
        "attraction" to mapOf("tourism" to "attraction"),
        "theme park" to mapOf("tourism" to "theme_park"),
        "zoo" to mapOf("tourism" to "zoo"),
        "aquarium" to mapOf("tourism" to "aquarium")
    )
    
    val LODGING = mapOf(
        "hotel" to mapOf("tourism" to "hotel"),
        "motel" to mapOf("tourism" to "motel"),
        "hostel" to mapOf("tourism" to "hostel"),
        "holiday inn" to mapOf("tourism" to "hotel", "brand" to "Holiday Inn", "brand:wikidata" to "Q2717882"),
        "marriott" to mapOf("tourism" to "hotel", "brand" to "Marriott", "brand:wikidata" to "Q1141173"),
        "hilton" to mapOf("tourism" to "hotel", "brand" to "Hilton", "brand:wikidata" to "Q598884"),
        "hampton inn" to mapOf("tourism" to "hotel", "brand" to "Hampton by Hilton", "brand:wikidata" to "Q5646230"),
        "best western" to mapOf("tourism" to "hotel", "brand" to "Best Western", "brand:wikidata" to "Q830334"),
        "la quinta" to mapOf("tourism" to "hotel", "brand" to "La Quinta", "brand:wikidata" to "Q6464734"),
        "super 8" to mapOf("tourism" to "motel", "brand" to "Super 8", "brand:wikidata" to "Q5364003"),
        "motel 6" to mapOf("tourism" to "motel", "brand" to "Motel 6", "brand:wikidata" to "Q2188884"),
        "days inn" to mapOf("tourism" to "hotel", "brand" to "Days Inn", "brand:wikidata" to "Q1189595"),
        "comfort inn" to mapOf("tourism" to "hotel", "brand" to "Comfort Inn", "brand:wikidata" to "Q5151196"),
        "quality inn" to mapOf("tourism" to "hotel", "brand" to "Quality Inn", "brand:wikidata" to "Q7268902"),
        "courtyard" to mapOf("tourism" to "hotel", "brand" to "Courtyard by Marriott", "brand:wikidata" to "Q1053170"),
        "fairfield inn" to mapOf("tourism" to "hotel", "brand" to "Fairfield Inn", "brand:wikidata" to "Q5430314"),
        "residence inn" to mapOf("tourism" to "hotel", "brand" to "Residence Inn", "brand:wikidata" to "Q7315394"),
        "hyatt" to mapOf("tourism" to "hotel", "brand" to "Hyatt", "brand:wikidata" to "Q1425063"),
        "sheraton" to mapOf("tourism" to "hotel", "brand" to "Sheraton", "brand:wikidata" to "Q634831"),
        "radisson" to mapOf("tourism" to "hotel", "brand" to "Radisson", "brand:wikidata" to "Q1751979"),
        "crowne plaza" to mapOf("tourism" to "hotel", "brand" to "Crowne Plaza", "brand:wikidata" to "Q2746220"),
        "embassy suites" to mapOf("tourism" to "hotel", "brand" to "Embassy Suites", "brand:wikidata" to "Q5369524"),
        "doubletree" to mapOf("tourism" to "hotel", "brand" to "DoubleTree", "brand:wikidata" to "Q2504643"),
        "extended stay" to mapOf("tourism" to "hotel", "brand" to "Extended Stay America", "brand:wikidata" to "Q5421077"),
        "red roof inn" to mapOf("tourism" to "motel", "brand" to "Red Roof Inn", "brand:wikidata" to "Q7304949")
    )
    
    private val ARTICLES = setOf("a", "an", "the", "some", "one", "small", "large", "big", "old", "new")

    /**
     * Lookup a feature by name (case-insensitive), with fuzzy fallback.
     * 1. Exact match
     * 2. Strip leading articles and retry
     * 3. Check if any dictionary key is contained within the input
     */
    fun lookupFeature(name: String): Map<String, String>? {
        val normalized = name.lowercase().trim()
        exactLookup(normalized)?.let { return it }

        // Strip leading articles ("a bench" -> "bench")
        val stripped = normalized.split(" ").dropWhile { it in ARTICLES }.joinToString(" ")
        if (stripped != normalized) exactLookup(stripped)?.let { return it }

        // Substring match: find the longest dictionary key contained in the input
        return getAllEntries()
            .filter { (key, _) -> normalized.contains(key) }
            .maxByOrNull { (key, _) -> key.length }
            ?.value
    }

    private fun exactLookup(normalized: String): Map<String, String>? =
        FAST_FOOD_BRANDS[normalized]
            ?: GAS_STATIONS[normalized]
            ?: RETAIL[normalized]
            ?: BANKS[normalized]
            ?: FOOD_AND_DRINK[normalized]
            ?: AMENITIES[normalized]
            ?: INFRASTRUCTURE[normalized]
            ?: OFFICES[normalized]
            ?: LODGING[normalized]

    private fun getAllEntries(): Map<String, Map<String, String>> =
        FAST_FOOD_BRANDS + GAS_STATIONS + RETAIL + BANKS +
        FOOD_AND_DRINK + AMENITIES + INFRASTRUCTURE + OFFICES + LODGING

    /**
     * Get all known feature names for fuzzy matching
     */
    fun getAllFeatureNames(): Set<String> = getAllEntries().keys
}
