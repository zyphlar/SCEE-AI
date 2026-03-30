package de.westnordost.streetcomplete.voicemapper

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import de.westnordost.streetcomplete.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object VoiceMapperExporter {

    /** Generates an OsmChange (.osc) document for the given pending edits.
     *
     *  CREATE_NODE edits go into <create> with new negative IDs.
     *  MODIFY_TAGS / MODIFY_NODE edits with a known elementKey go into <modify>
     *  using the real OSM element ID (version="0" as placeholder — JOSM fetches
     *  the real version on load).
     *  DELETE_NODE edits with a known elementKey go into <delete>.
     *  Any non-create edit that lacks an elementKey falls back to <create> as a
     *  note node so the intent is not silently lost.
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
                    // fallback note node for edits without a resolved element
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
                val posAttrs = if (pos != null) " lat=\"${pos.latitude}\" lon=\"${pos.longitude}\"" else ""
                sb.append("    <$tag id=\"${key.id}\"$posAttrs version=\"1\">\n")
                for ((k, v) in edit.tags) {
                    sb.append("      <tag k=\"${k.xmlEscape()}\" v=\"${v.xmlEscape()}\"/>\n")
                }
                sb.append("      <tag k=\"note\" v=\"${edit.description.xmlEscape()}\"/>\n")
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
                val posAttrs = if (pos != null) " lat=\"${pos.latitude}\" lon=\"${pos.longitude}\"" else ""
                sb.append("    <$tag id=\"${key.id}\"$posAttrs version=\"1\"/>\n")
            }
            sb.append("  </delete>\n")
        }

        sb.append("</osmChange>\n")
        return sb.toString()
    }

    /** Writes a .osc file to the cache dir and returns a content URI for it. */
    internal fun buildOsmFileUri(context: Context, edits: List<VoiceMapperEdit>): Uri {
        val xml = generateOsmChange(edits)
        val file = File(context.cacheDir, "scee_ai_export.osc")
        file.writeText(xml, Charsets.UTF_8)
        return FileProvider.getUriForFile(
            context,
            context.getString(R.string.fileprovider_authority),
            file
        )
    }

    /** Writes a .osm file to the cache dir and opens the Android share sheet. */
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

    /** Opens the iD web editor in a browser, centred on the mean position of the edits. */
    fun openInId(context: Context, edits: List<VoiceMapperEdit>) {
        val positions = edits.mapNotNull { it.position }
        if (positions.isEmpty()) return
        val lat = positions.map { it.latitude }.average()
        val lon = positions.map { it.longitude }.average()
        val uri = Uri.parse("https://www.openstreetmap.org/edit?editor=id#map=18/$lat/$lon")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun String.xmlEscape() = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
