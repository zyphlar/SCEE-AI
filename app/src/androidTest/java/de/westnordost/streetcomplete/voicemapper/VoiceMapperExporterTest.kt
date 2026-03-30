package de.westnordost.streetcomplete.voicemapper

import androidx.test.platform.app.InstrumentationRegistry
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.ElementType
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoiceMapperExporterTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun makeEdit(
        type: EditType = EditType.CREATE_NODE,
        lat: Double = 44.938,
        lon: Double = -123.022,
        tags: Map<String, String> = mapOf("amenity" to "bench"),
        description: String = "Test edit",
        transcription: String? = "add a bench here",
        elementKey: ElementKey? = null
    ) = VoiceMapperEdit(
        type = type,
        description = description,
        tags = tags,
        position = LatLon(lat, lon),
        sourceTranscription = transcription,
        elementKey = elementKey
    )

    // ── FileProvider / cache path ──────────────────────────────────────────────

    @Test
    fun buildOsmFileUri_returnsContentUri() {
        val uri = VoiceMapperExporter.buildOsmFileUri(context, listOf(makeEdit()))
        assertNotNull(uri)
        assertEquals("content", uri.scheme)
    }

    @Test
    fun buildOsmFileUri_multipleEdits_doesNotThrow() {
        val edits = listOf(
            makeEdit(EditType.CREATE_NODE, tags = mapOf("amenity" to "bench")),
            makeEdit(EditType.MODIFY_TAGS, tags = mapOf("name" to "Park"), elementKey = ElementKey(ElementType.NODE, 42L)),
            makeEdit(EditType.DELETE_NODE, tags = emptyMap(), elementKey = ElementKey(ElementType.NODE, 99L))
        )
        val uri = VoiceMapperExporter.buildOsmFileUri(context, edits)
        assertNotNull(uri)
    }

    @Test
    fun buildOsmFileUri_writesReadableOscFile() {
        VoiceMapperExporter.buildOsmFileUri(context, listOf(makeEdit()))
        val file = java.io.File(context.cacheDir, "scee_ai_export.osc")
        assertTrue(file.exists(), "cache file should exist after export")
        assertTrue(file.length() > 0, "cache file should not be empty")
    }

    // ── XML generation ─────────────────────────────────────────────────────────

    @Test
    fun generateOsmChange_hasOsmChangeRoot() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(makeEdit()))
        assertTrue(xml.contains("<?xml"), "should be valid XML")
        assertTrue(xml.contains("<osmChange"), "should have osmChange root")
        assertTrue(xml.contains("</osmChange>"), "should close osmChange root")
    }

    @Test
    fun generateOsmChange_createNodeGoesInCreateBlock() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(makeEdit(type = EditType.CREATE_NODE)))
        assertTrue(xml.contains("<create>"), "CREATE_NODE should produce <create> block")
        assertTrue(xml.contains("<node "), "should contain node element")
        assertTrue(!xml.contains("<modify>"), "should not have modify block")
        assertTrue(!xml.contains("<delete>"), "should not have delete block")
    }

    @Test
    fun generateOsmChange_createNode_hasNegativeId() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(makeEdit(type = EditType.CREATE_NODE)))
        assertTrue(xml.contains("id=\"-"), "new node should have negative ID")
    }

    @Test
    fun generateOsmChange_createNode_hasVersionZero() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(makeEdit(type = EditType.CREATE_NODE)))
        assertTrue(xml.contains("version=\"0\""), "new node should have version=0")
    }

    @Test
    fun generateOsmChange_createNode_hasLatLon() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(makeEdit(lat = 44.938, lon = -123.022)))
        assertTrue(xml.contains("lat=\"44.938\""), "should include latitude")
        assertTrue(xml.contains("lon=\"-123.022\""), "should include longitude")
    }

    @Test
    fun generateOsmChange_createNode_containsTags() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(makeEdit(
            type = EditType.CREATE_NODE,
            tags = mapOf("amenity" to "bench")
        )))
        assertTrue(xml.contains("amenity"), "should contain tag key")
        assertTrue(xml.contains("bench"), "should contain tag value")
    }

    @Test
    fun generateOsmChange_modifyWithElementKey_goesInModifyBlock() {
        val key = ElementKey(ElementType.NODE, 12345L)
        val xml = VoiceMapperExporter.generateOsmChange(listOf(
            makeEdit(type = EditType.MODIFY_TAGS, elementKey = key)
        ))
        assertTrue(xml.contains("<modify>"), "MODIFY with elementKey should produce <modify> block")
        assertTrue(xml.contains("id=\"12345\""), "should use real OSM element ID")
        assertTrue(!xml.contains("<create>"), "should not have create block")
    }

    @Test
    fun generateOsmChange_deleteWithElementKey_goesInDeleteBlock() {
        val key = ElementKey(ElementType.NODE, 99L)
        val xml = VoiceMapperExporter.generateOsmChange(listOf(
            makeEdit(type = EditType.DELETE_NODE, elementKey = key)
        ))
        assertTrue(xml.contains("<delete>"), "DELETE with elementKey should produce <delete> block")
        assertTrue(xml.contains("id=\"99\""), "should use real OSM element ID")
        assertTrue(!xml.contains("<modify>"), "should not have modify block")
    }

    @Test
    fun generateOsmChange_modifyWithoutElementKey_fallsBackToCreate() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(
            makeEdit(type = EditType.MODIFY_TAGS, elementKey = null)
        ))
        assertTrue(xml.contains("<create>"), "MODIFY without elementKey should fall back to <create>")
        assertTrue(xml.contains("fixme"), "fallback node should include fixme tag")
    }

    @Test
    fun generateOsmChange_escapesSpecialChars() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(makeEdit(
            description = "A & B <test> \"quoted\" 'apos'",
            tags = mapOf("note" to "a&b")
        )))
        assertTrue(xml.contains("&amp;"), "should escape ampersand")
        assertTrue(xml.contains("&lt;"), "should escape less-than")
        assertTrue(xml.contains("&gt;"), "should escape greater-than")
        assertTrue(xml.contains("&quot;"), "should escape double-quote")
        assertTrue(xml.contains("&apos;"), "should escape apostrophe")
    }

    @Test
    fun generateOsmChange_includesSourceTranscription() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(makeEdit(transcription = "add a bench here")))
        assertTrue(xml.contains("voice_mapper:source"), "should include transcription tag")
        assertTrue(xml.contains("add a bench here"), "should include transcription value")
    }

    @Test
    fun generateOsmChange_nullTranscription_omitsSourceTag() {
        val xml = VoiceMapperExporter.generateOsmChange(listOf(makeEdit(transcription = null)))
        assertTrue(!xml.contains("voice_mapper:source"), "null transcription should omit source tag")
    }

    @Test
    fun generateOsmChange_skipsEditsWithNoPosition() {
        val editNoPos = VoiceMapperEdit(type = EditType.CREATE_NODE, description = "no position")
        val xml = VoiceMapperExporter.generateOsmChange(listOf(editNoPos))
        assertTrue(!xml.contains("<node "), "edit with null position should be skipped")
    }

    @Test
    fun generateOsmChange_emptyEdits_returnsValidDocument() {
        val xml = VoiceMapperExporter.generateOsmChange(emptyList())
        assertTrue(xml.contains("<?xml"), "should be valid XML")
        assertTrue(xml.contains("<osmChange"), "should contain osmChange root element")
        assertTrue(!xml.contains("<node"), "empty list should produce no nodes")
    }

    @Test
    fun generateOsmChange_wayElementType_usesWayTag() {
        val key = ElementKey(ElementType.WAY, 555L)
        val xml = VoiceMapperExporter.generateOsmChange(listOf(
            makeEdit(type = EditType.MODIFY_TAGS, elementKey = key)
        ))
        assertTrue(xml.contains("<way "), "WAY element type should produce <way> element")
        assertTrue(xml.contains("</way>"), "should close way element")
    }
}
