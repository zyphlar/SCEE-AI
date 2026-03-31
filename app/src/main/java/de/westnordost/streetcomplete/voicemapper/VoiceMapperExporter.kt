package de.westnordost.streetcomplete.voicemapper

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.mapdata.ElementType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object VoiceMapperExporter {

    /**
     * Generates a standalone OSM (.osm) document for the given pending edits.
     *
     * Suitable for opening directly in JOSM — all geometry is self-contained:
     *   CREATE_NODE  → new node with tags at the recorded position
     *   MODIFY       → element with its real OSM ID, merged tags, and (for ways) all
     *                  referenced nodes embedded with their actual lat/lon positions
     *   DELETE       → placeholder node at the centroid with a fixme tag (safe for review)
     *   fallback     → placeholder note node for edits without a resolved element
     */
    fun generateOsmXml(edits: List<VoiceMapperEdit>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<osm version=\"0.6\">\n")
        var nextId = -1L

        // Collect all referenced node positions across all edits (for way geometry)
        val allNodePositions = edits.fold(mutableMapOf<Long, de.westnordost.streetcomplete.data.osm.mapdata.LatLon>()) { acc, edit ->
            acc.putAll(edit.nodePositions); acc
        }
        for ((nodeId, pos) in allNodePositions) {
            sb.append("  <node id=\"$nodeId\" lat=\"${pos.latitude}\" lon=\"${pos.longitude}\" version=\"1\" visible=\"true\"/>\n")
        }

        for (edit in edits) {
            val pos = edit.position ?: continue
            val key = edit.elementKey
            val version = edit.elementVersion ?: 1

            when {
                edit.type == EditType.CREATE_NODE -> {
                    val id = nextId--
                    sb.append("  <node id=\"$id\" lat=\"${pos.latitude}\" lon=\"${pos.longitude}\" version=\"0\" visible=\"true\">\n")
                    for ((k, v) in edit.tags) {
                        sb.append("    <tag k=\"${k.xmlEscape()}\" v=\"${v.xmlEscape()}\"/>\n")
                    }
                    sb.append("    <tag k=\"note\" v=\"${edit.description.xmlEscape()}\"/>\n")
                    edit.sourceTranscription?.let {
                        sb.append("    <tag k=\"voice_mapper:source\" v=\"${it.xmlEscape()}\"/>\n")
                    }
                    sb.append("  </node>\n")
                }

                key != null && edit.type in listOf(EditType.MODIFY_TAGS, EditType.MODIFY_NODE) -> {
                    val tag = key.type.name.lowercase()
                    val posAttrs = if (key.type == ElementType.NODE) " lat=\"${pos.latitude}\" lon=\"${pos.longitude}\"" else ""
                    sb.append("  <$tag id=\"${key.id}\"$posAttrs version=\"$version\" visible=\"true\">\n")
                    for (nodeId in edit.wayNodeIds) {
                        sb.append("    <nd ref=\"$nodeId\"/>\n")
                    }
                    for (member in edit.relationMembers) {
                        sb.append("    <member type=\"${member.type.name.lowercase()}\" ref=\"${member.ref}\" role=\"${member.role.xmlEscape()}\"/>\n")
                    }
                    val mergedTags = (edit.originalTags + edit.tags).filterKeys { it !in edit.tagsToRemove }
                    for ((k, v) in mergedTags) {
                        sb.append("    <tag k=\"${k.xmlEscape()}\" v=\"${v.xmlEscape()}\"/>\n")
                    }
                    edit.sourceTranscription?.let {
                        sb.append("    <tag k=\"voice_mapper:source\" v=\"${it.xmlEscape()}\"/>\n")
                    }
                    sb.append("  </$tag>\n")
                }

                key != null && edit.type == EditType.DELETE_NODE -> {
                    // Placeholder node so the location is visible; reviewer handles the actual delete
                    val id = nextId--
                    sb.append("  <node id=\"$id\" lat=\"${pos.latitude}\" lon=\"${pos.longitude}\" version=\"0\" visible=\"true\">\n")
                    sb.append("    <tag k=\"fixme\" v=\"delete ${key.type.name.lowercase()} ${key.id}\"/>\n")
                    sb.append("    <tag k=\"note\" v=\"${edit.description.xmlEscape()}\"/>\n")
                    edit.sourceTranscription?.let {
                        sb.append("    <tag k=\"voice_mapper:source\" v=\"${it.xmlEscape()}\"/>\n")
                    }
                    sb.append("  </node>\n")
                }

                else -> {
                    // Fallback: no resolved element — placeholder note node
                    val id = nextId--
                    sb.append("  <node id=\"$id\" lat=\"${pos.latitude}\" lon=\"${pos.longitude}\" version=\"0\" visible=\"true\">\n")
                    sb.append("    <tag k=\"note\" v=\"${edit.description.xmlEscape()}\"/>\n")
                    sb.append("    <tag k=\"fixme\" v=\"${edit.type.name.lowercase().replace('_', ' ')}\"/>\n")
                    edit.sourceTranscription?.let {
                        sb.append("    <tag k=\"voice_mapper:source\" v=\"${it.xmlEscape()}\"/>\n")
                    }
                    sb.append("  </node>\n")
                }
            }
        }

        sb.append("</osm>\n")
        return sb.toString()
    }

    /**
     * Generates an OsmChange (.osc) document for the given pending edits.
     *
     * Suitable for JOSM Remote Control (load_data) or technical import workflows.
     * JOSM must have the affected area already downloaded to render modified elements.
     *   CREATE_NODE  → <create> with negative IDs
     *   MODIFY       → <modify> with real element ID, actual version, merged tags, nd refs
     *   DELETE       → <delete> with real element ID and version
     *   fallback     → <create> note node for unresolved edits
     */
    fun generateOsmChange(edits: List<VoiceMapperEdit>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<osmChange version=\"0.6\">\n")

        val creates = edits.filter { it.type == EditType.CREATE_NODE || (it.type != EditType.CREATE_NODE && it.elementKey == null) }
        val modifies = edits.filter { it.type in listOf(EditType.MODIFY_TAGS, EditType.MODIFY_NODE) && it.elementKey != null }
        val deletes = edits.filter { it.type == EditType.DELETE_NODE && it.elementKey != null }

        var nextId = -1L

        if (creates.isNotEmpty()) {
            sb.append("  <create>\n")
            for (edit in creates) {
                val pos = edit.position ?: continue
                val id = nextId--
                sb.append("    <node id=\"$id\" lat=\"${pos.latitude}\" lon=\"${pos.longitude}\" version=\"0\">\n")
                for ((k, v) in edit.tags) {
                    sb.append("      <tag k=\"${k.xmlEscape()}\" v=\"${v.xmlEscape()}\"/>\n")
                }
                if (edit.type != EditType.CREATE_NODE) {
                    sb.append("      <tag k=\"note\" v=\"${edit.description.xmlEscape()}\"/>\n")
                    sb.append("      <tag k=\"fixme\" v=\"${edit.type.name.lowercase().replace('_', ' ')}\"/>\n")
                } else {
                    sb.append("      <tag k=\"note\" v=\"${edit.description.xmlEscape()}\"/>\n")
                }
                edit.sourceTranscription?.let {
                    sb.append("      <tag k=\"voice_mapper:source\" v=\"${it.xmlEscape()}\"/>\n")
                }
                sb.append("    </node>\n")
            }
            sb.append("  </create>\n")
        }

        if (modifies.isNotEmpty()) {
            sb.append("  <modify>\n")
            for (edit in modifies) {
                val key = edit.elementKey!!
                val pos = edit.position
                val tag = key.type.name.lowercase()
                val posAttrs = if (pos != null && key.type == ElementType.NODE) " lat=\"${pos.latitude}\" lon=\"${pos.longitude}\"" else ""
                val version = edit.elementVersion ?: 1
                sb.append("    <$tag id=\"${key.id}\"$posAttrs version=\"$version\">\n")
                for (nodeId in edit.wayNodeIds) {
                    sb.append("      <nd ref=\"$nodeId\"/>\n")
                }
                for (member in edit.relationMembers) {
                    sb.append("      <member type=\"${member.type.name.lowercase()}\" ref=\"${member.ref}\" role=\"${member.role.xmlEscape()}\"/>\n")
                }
                val mergedTags = (edit.originalTags + edit.tags).filterKeys { it !in edit.tagsToRemove }
                for ((k, v) in mergedTags) {
                    sb.append("      <tag k=\"${k.xmlEscape()}\" v=\"${v.xmlEscape()}\"/>\n")
                }
                edit.sourceTranscription?.let {
                    sb.append("      <tag k=\"voice_mapper:source\" v=\"${it.xmlEscape()}\"/>\n")
                }
                sb.append("    </$tag>\n")
            }
            sb.append("  </modify>\n")
        }

        if (deletes.isNotEmpty()) {
            sb.append("  <delete>\n")
            for (edit in deletes) {
                val key = edit.elementKey!!
                val pos = edit.position
                val tag = key.type.name.lowercase()
                val posAttrs = if (pos != null && key.type == ElementType.NODE) " lat=\"${pos.latitude}\" lon=\"${pos.longitude}\"" else ""
                val version = edit.elementVersion ?: 1
                sb.append("    <$tag id=\"${key.id}\"$posAttrs version=\"$version\"/>\n")
            }
            sb.append("  </delete>\n")
        }

        sb.append("</osmChange>\n")
        return sb.toString()
    }

    /** Writes a .osm file to the cache dir and returns a content URI for it. */
    internal fun buildOsmFileUri(context: Context, edits: List<VoiceMapperEdit>): Uri {
        val xml = generateOsmXml(edits)
        val file = File(context.cacheDir, "scee_ai_export.osm")
        file.writeText(xml, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, context.getString(R.string.fileprovider_authority), file)
    }

    /** Writes a .osc file to the cache dir and returns a content URI for it. */
    internal fun buildOscFileUri(context: Context, edits: List<VoiceMapperEdit>): Uri {
        val xml = generateOsmChange(edits)
        val file = File(context.cacheDir, "scee_ai_export.osc")
        file.writeText(xml, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, context.getString(R.string.fileprovider_authority), file)
    }

    /** Shares a standalone .osm file via the Android share sheet (opens directly in JOSM). */
    fun shareAsOsmFile(context: Context, edits: List<VoiceMapperEdit>) {
        val uri = buildOsmFileUri(context, edits)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-osm+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "SCEE-AI pending edits")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export pending edits"))
    }

    /** Shares an OsmChange .osc file via the Android share sheet. */
    fun shareAsOscFile(context: Context, edits: List<VoiceMapperEdit>) {
        val uri = buildOscFileUri(context, edits)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-osm+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "SCEE-AI pending edits")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export pending edits"))
    }

    /**
     * Sends edits to JOSM via its Remote Control API.
     * JOSM must be running on [host]:[port] with Remote Control enabled (Edit → Preferences → Remote Control).
     * Returns a Result indicating success or the error that occurred.
     */
    suspend fun sendToJosm(edits: List<VoiceMapperEdit>, host: String, port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val xml = generateOsmChange(edits)
                val encoded = URLEncoder.encode(xml, "UTF-8")
                val url = URL("http://$host:$port/load_data?new_layer=true&data=$encoded")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                val code = conn.responseCode
                if (code in 200..299) Result.success(Unit)
                else Result.failure(Exception("JOSM returned HTTP $code"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun String.xmlEscape() = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
