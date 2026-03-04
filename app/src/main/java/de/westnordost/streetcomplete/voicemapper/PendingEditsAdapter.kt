package de.westnordost.streetcomplete.voicemapper

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.westnordost.streetcomplete.R

/**
 * Adapter for displaying pending voice mapper edits
 * 
 * Each item shows:
 * - Edit description
 * - Confidence indicator (color coded)
 * - Confirm/Reject/Edit buttons
 * - Tags preview
 */
class PendingEditsAdapter(
    private val onConfirm: (VoiceMapperEdit) -> Unit,
    private val onReject: (VoiceMapperEdit) -> Unit,
    private val onEdit: (VoiceMapperEdit) -> Unit
) : ListAdapter<VoiceMapperEdit, PendingEditsAdapter.ViewHolder>(DiffCallback) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pending_edit, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: CardView = itemView.findViewById(R.id.editCard)
        private val descriptionText: TextView = itemView.findViewById(R.id.descriptionText)
        private val tagsText: TextView = itemView.findViewById(R.id.tagsText)
        private val confidenceText: TextView = itemView.findViewById(R.id.confidenceText)
        private val sideText: TextView = itemView.findViewById(R.id.sideText)
        private val confirmButton: ImageButton = itemView.findViewById(R.id.confirmButton)
        private val rejectButton: ImageButton = itemView.findViewById(R.id.rejectButton)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        private val explanationText: TextView = itemView.findViewById(R.id.explanationText)
        
        fun bind(edit: VoiceMapperEdit) {
            // Description
            descriptionText.text = edit.description
            
            // Tags preview
            val tagPreview = edit.tags.entries.take(3).joinToString("\n") { (key, value) ->
                "$key = $value"
            }
            tagsText.text = tagPreview
            tagsText.visibility = if (tagPreview.isNotBlank()) View.VISIBLE else View.GONE
            
            // Confidence indicator
            val confidencePercent = (edit.confidence * 100).toInt()
            confidenceText.text = "$confidencePercent%"
            confidenceText.setTextColor(getConfidenceColor(edit.confidence))
            
            // Card border color based on confidence
            card.setCardBackgroundColor(
                when {
                    edit.confidence >= 0.9f -> ContextCompat.getColor(itemView.context, R.color.confidence_high_bg)
                    edit.confidence >= 0.7f -> ContextCompat.getColor(itemView.context, R.color.confidence_medium_bg)
                    else -> ContextCompat.getColor(itemView.context, R.color.confidence_low_bg)
                }
            )
            
            // Side indicator
            sideText.text = when (edit.relativeSide) {
                RelativeSide.LEFT -> "← Left"
                RelativeSide.RIGHT -> "Right →"
                RelativeSide.CENTER -> "↑ Ahead"
                null -> ""
            }
            sideText.visibility = if (edit.relativeSide != null) View.VISIBLE else View.GONE
            
            // AI explanation
            explanationText.text = edit.aiExplanation ?: ""
            explanationText.visibility = if (edit.aiExplanation != null) View.VISIBLE else View.GONE
            
            // Edit type icon/indicator
            val typeIcon = when (edit.type) {
                EditType.CREATE_NODE -> "+"
                EditType.MODIFY_NODE -> "✏"
                EditType.DELETE_NODE -> "×"
                EditType.MODIFY_TAGS -> "🏷"
            }
            descriptionText.text = "$typeIcon ${edit.description}"
            
            // Button clicks
            confirmButton.setOnClickListener { onConfirm(edit) }
            rejectButton.setOnClickListener { onReject(edit) }
            editButton.setOnClickListener { onEdit(edit) }
            
            // Long press for quick actions
            itemView.setOnLongClickListener {
                // Show quick action menu
                onEdit(edit)
                true
            }
        }
        
        private fun getConfidenceColor(confidence: Float): Int {
            return when {
                confidence >= 0.9f -> Color.parseColor("#2E7D32") // Dark green
                confidence >= 0.7f -> Color.parseColor("#F57C00") // Orange
                else -> Color.parseColor("#C62828") // Red
            }
        }
    }
    
    object DiffCallback : DiffUtil.ItemCallback<VoiceMapperEdit>() {
        override fun areItemsTheSame(oldItem: VoiceMapperEdit, newItem: VoiceMapperEdit): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: VoiceMapperEdit, newItem: VoiceMapperEdit): Boolean {
            return oldItem == newItem
        }
    }
}
