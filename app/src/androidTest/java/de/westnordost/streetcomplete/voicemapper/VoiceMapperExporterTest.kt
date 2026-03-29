package de.westnordost.streetcomplete.voicemapper

import androidx.test.platform.app.InstrumentationRegistry
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
        transcription: String? = "add a bench here"
    ) = VoiceMapperEdit(
        type = type,
        description = description,
        tags = tags,
        position = LatLon(lat, lon),
        sourceTranscription = transcription
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
            makeEdit(EditType.MODIFY_TAGS, tags = mapOf("name" to "Park")),
            makeEdit(EditType.DELETE_NODE, tags = emptyMap())
        )
        val uri = VoiceMapperExporter.buildOsmFileUri(context, edits)
        assertNotNull(uri)
    }

    @Test
    fun buildOsmFileUri_writesReadableFile() {
        VoiceMapperExporter.buildOsmFileUri(context, listOf(makeEdit()))
        val file = java.io.File(context.cacheDir, "scee_ai_export.osm")
        assertTrue(file.exists(), "cache file should exist after export")
        assertTrue(file.length() > 0, "cache file should not be empty")
    }

    // ── XML generation ─────────────────────────────────────────────────────────

    @Test
    fun generateOsmXml_containsNodeWithTags() {
        val xml = VoiceMapperExporter.generateOsmXml(listOf(makeEdit(
            tags = mapOf("amenity" to "bench"),
            description = "add bench"
        )))
        assertTrue(xml.contains("<node "), "should contain a node element")
        assertTrue(xml.contains("amenity"), "should contain amenity tag")
        assertTrue(xml.contains("bench"), "should contain bench value")
    }

    @Test
    fun generateOsmXml_escapesSpecialChars() {
        val xml = VoiceMapperExporter.generateOsmXml(listOf(makeEdit(
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
    fun generateOsmXml_nonCreateEdit_includesFixmeTag() {
        val xml = VoiceMapperExporter.generateOsmXml(listOf(makeEdit(type = EditType.MODIFY_TAGS)))
        assertTrue(xml.contains("fixme"), "non-CREATE edit should include fixme tag")
    }

    @Test
    fun generateOsmXml_createNodeEdit_noFixmeTag() {
        val xml = VoiceMapperExporter.generateOsmXml(listOf(makeEdit(type = EditType.CREATE_NODE)))
        assertTrue(!xml.contains("fixme"), "CREATE_NODE edit should not include fixme tag")
    }

    @Test
    fun generateOsmXml_includesSourceTranscription() {
        val xml = VoiceMapperExporter.generateOsmXml(listOf(makeEdit(transcription = "add a bench here")))
        assertTrue(xml.contains("voice_mapper:source"), "should include transcription tag")
        assertTrue(xml.contains("add a bench here"), "should include transcription value")
    }

    @Test
    fun generateOsmXml_nullTranscription_omitsSourceTag() {
        val xml = VoiceMapperExporter.generateOsmXml(listOf(makeEdit(transcription = null)))
        assertTrue(!xml.contains("voice_mapper:source"), "null transcription should omit source tag")
    }

    @Test
    fun generateOsmXml_skipsEditsWithNoPosition() {
        val editNoPos = VoiceMapperEdit(type = EditType.CREATE_NODE, description = "no position")
        val xml = VoiceMapperExporter.generateOsmXml(listOf(editNoPos))
        assertTrue(!xml.contains("<node "), "edit with null position should be skipped")
    }

    @Test
    fun generateOsmXml_emptyEdits_returnsValidDocument() {
        val xml = VoiceMapperExporter.generateOsmXml(emptyList())
        assertTrue(xml.contains("<?xml"), "should be valid XML")
        assertTrue(xml.contains("<osm"), "should contain osm root element")
        assertTrue(!xml.contains("<node"), "empty list should produce no nodes")
    }
}
