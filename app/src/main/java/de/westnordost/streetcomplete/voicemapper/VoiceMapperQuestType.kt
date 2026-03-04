package de.westnordost.streetcomplete.voicemapper

import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.edits.ElementEditType

/**
 * "Quest type" for voice mapper edits
 *
 * This is not a real quest - it's used to categorize edits made via voice mapper
 * for proper changeset tagging and statistics.
 */
object VoiceMapperQuestType : ElementEditType {

    override val name: String = "VoiceMapper"

    override val changesetComment: String = "Voice mapped POIs"

    override val wikiLink: String? = null

    override val icon: Int = R.drawable.ic_mic

    override val title: Int = R.string.voice_mapper

    override fun toString(): String = name
}

/**
 * Statistics tracking for voice mapper usage
 */
data class VoiceMapperStats(
    val totalEdits: Int = 0,
    val nodesCreated: Int = 0,
    val nodesModified: Int = 0,
    val nodesDeleted: Int = 0,
    val tagsModified: Int = 0,
    val averageConfidence: Float = 0f,
    val mostCommonPOIType: String? = null,
    val sessionStartTime: Long = 0,
    val sessionDuration: Long = 0
)

/**
 * Voice mapper preferences keys
 */
object VoiceMapperPrefs {
    const val KEY_AUDIO_FEEDBACK = "voice_mapper_audio_feedback"
    const val KEY_AUTO_CONFIRM = "voice_mapper_auto_confirm"
    const val KEY_DEFAULT_SIDE = "voice_mapper_default_side"
    const val KEY_DEFAULT_DISTANCE = "voice_mapper_default_distance"
    const val KEY_API_ENDPOINT = "voice_mapper_api_endpoint"
    const val KEY_CONTINUOUS_MODE = "voice_mapper_continuous_mode"
    const val KEY_OFFLINE_MODE = "voice_mapper_offline_mode"

    const val DEFAULT_DISTANCE = 15.0
    const val DEFAULT_SIDE = "RIGHT"
}
