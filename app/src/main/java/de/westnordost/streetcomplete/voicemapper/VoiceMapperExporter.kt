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

    /** Generates an OSM XML document for the given pending edits.
     *
     *  CREATE_NODE edits become new nodes with their resolved tags.
     *  MODIFY/DELETE edits become placeholder nodes at the edit position
     *  with a `fixme` tag describing the intended operation, since the
     *  full existing element data isn't carried in VoiceMapperEdit.
     */
    fun generateOsmXml(edits: List<VoiceMapperEdit>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<osm version=\"0.6\">\n")
        var nextId = -1L
        for (edit in edits) {
            val pos = edit.position ?: continue
            val id = nextId--
            sb.append("  <node id=\"$id\" lat=\"${pos.latitude}\" lon=\"${pos.longitude}\" visible=\"true\">\n")
            sb.append("    <tag k=\"note\" v=\"${edit.description.xmlEscape()}\"/>\n")
            for ((k, v) in edit.tags) {
                sb.append("    <tag k=\"${k.xmlEscape()}\" v=\"${v.xmlEscape()}\"/>\n")
            }
            if (edit.type != EditType.CREATE_NODE) {
                sb.append("    <tag k=\"fixme\" v=\"${edit.type.name.lowercase().replace('_', ' ')}\"/>\n")
            }
            edit.sourceTranscription?.let {
                sb.append("    <tag k=\"voice_mapper:source\" v=\"${it.xmlEscape()}\"/>\n")
            }
            sb.append("  </node>\n")
        }
        sb.append("</osm>\n")
        return sb.toString()
    }

    /** Writes a .osm file to the cache dir and opens the Android share sheet. */
    fun shareAsOsmFile(context: Context, edits: List<VoiceMapperEdit>) {
        val xml = generateOsmXml(edits)
        val file = File(context.cacheDir, "scee_ai_export.osm")
        file.writeText(xml, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            context.getString(R.string.fileprovider_authority),
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/xml"
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
                val xml = generateOsmXml(edits)
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
