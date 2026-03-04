package de.westnordost.streetcomplete.voicemapper

import androidx.compose.ui.graphics.Color
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataWithGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.data.overlays.AndroidOverlay
import de.westnordost.streetcomplete.data.overlays.Overlay
import de.westnordost.streetcomplete.data.overlays.OverlayStyle
import de.westnordost.streetcomplete.overlays.AbstractOverlayForm

/**
 * Overlay that shows pending voice mapper edits as pins on the map.
 *
 * Pending edits (not yet submitted to OSM) are shown with the microphone icon.
 * Tapping a pin opens a form to inspect/edit the tags before confirming.
 */
class VoiceMapperOverlay(
    private val voiceMapperService: VoiceMapperService
) : Overlay, AndroidOverlay {

    override val title = R.string.voice_mapper
    override val icon = R.drawable.ic_mic
    override val changesetComment = "Voice mapped POIs"
    override val wikiLink: String? = null
    override val isCreateNodeEnabled = false

    override fun getStyledElements(mapData: MapDataWithGeometry): Sequence<Pair<Element, OverlayStyle>> {
        val pending = voiceMapperService.pendingEdits.value
        val submitted = voiceMapperService.submittedEdits.value
        return (pending.asSequence().map { it to false } + submitted.asSequence().map { it to true })
            .mapNotNull { (edit, isSubmitted) ->
                val position = edit.position ?: return@mapNotNull null
                val node = Node(
                    id = voiceMapperService.nodeIdForEdit(edit),
                    position = position,
                    tags = edit.tags,
                    version = 0
                )
                val label = edit.tags["name"]
                    ?: edit.tags["brand"]
                    ?: edit.tags["amenity"]
                    ?: edit.tags["shop"]
                    ?: edit.description.take(20)
                val icon = if (isSubmitted) R.drawable.ic_check else R.drawable.ic_mic
                val color = if (isSubmitted) Color(0xFF4CAF50) else null  // green for submitted
                node to OverlayStyle.Point(icon, label, color)
            }
    }

    // createForm is not used for synthetic nodes — MainActivity handles tap directly
    override fun createForm(element: Element?): AbstractOverlayForm? = null
}
