package de.westnordost.streetcomplete.voicemapper

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.westnordost.streetcomplete.R
import org.koin.androidx.viewmodel.ext.android.getViewModel

/**
 * Dialog for editing a pending voice mapper edit before confirmation
 * 
 * Allows modification of:
 * - Tags (add/edit/remove)
 * - Position (relative side and distance)
 * - Description
 */
class VoiceMapperEditDialogFragment : DialogFragment() {
    
    private lateinit var edit: VoiceMapperEdit
    private val tags = mutableMapOf<String, String>()
    private var tagsAdapter: TagsAdapter? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edit = arguments?.getParcelableCompat(ARG_EDIT) ?: run {
            dismiss()
            return
        }
        tags.putAll(edit.tags)
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_edit_voice_mapper, null)
        
        setupViews(view)
        
        return AlertDialog.Builder(requireContext())
            .setTitle("Edit Before Adding")
            .setView(view)
            .setPositiveButton("Confirm") { _, _ ->
                confirmEdit()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                deleteEdit()
            }
            .create()
    }
    
    private fun setupViews(view: View) {
        val descriptionEdit = view.findViewById<EditText>(R.id.descriptionEdit)
        val sideSpinner = view.findViewById<Spinner>(R.id.sideSpinner)
        val distanceAheadEdit = view.findViewById<EditText>(R.id.distanceAheadEdit)
        val distanceSideEdit = view.findViewById<EditText>(R.id.distanceSideEdit)
        val tagsRecyclerView = view.findViewById<RecyclerView>(R.id.tagsRecyclerView)
        val addTagButton = view.findViewById<Button>(R.id.addTagButton)
        val suggestedTagsLayout = view.findViewById<LinearLayout>(R.id.suggestedTagsLayout)
        
        // Description
        descriptionEdit.setText(edit.description)
        
        // Side spinner
        val sides = arrayOf("Left", "Right", "Ahead")
        val sideAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sides)
        sideAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sideSpinner.adapter = sideAdapter
        sideSpinner.setSelection(
            when (edit.relativeSide) {
                RelativeSide.LEFT -> 0
                RelativeSide.RIGHT -> 1
                RelativeSide.CENTER -> 2
                null -> 1 // Default to right
            }
        )
        
        // Distance
        distanceAheadEdit.setText(edit.distanceAhead.toString())
        distanceSideEdit.setText(edit.distanceSide.toString())
        
        // Tags RecyclerView
        tagsAdapter = TagsAdapter(tags) { key ->
            tags.remove(key)
            tagsAdapter?.notifyDataSetChanged()
        }
        tagsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tagsAdapter
        }
        
        // Add tag button
        addTagButton.setOnClickListener {
            showAddTagDialog()
        }
        
        // Suggested tags based on current tags
        setupSuggestedTags(suggestedTagsLayout)
    }
    
    private fun setupSuggestedTags(layout: LinearLayout) {
        layout.removeAllViews()
        
        val suggestions = getSuggestedTags()
        for ((key, value) in suggestions) {
            if (!tags.containsKey(key)) {
                val chip = TextView(requireContext()).apply {
                    text = "$key=$value"
                    setPadding(16, 8, 16, 8)
                    setBackgroundResource(R.drawable.bg_chip)
                    setOnClickListener {
                        tags[key] = value
                        tagsAdapter?.notifyDataSetChanged()
                        layout.removeView(this)
                    }
                }
                layout.addView(chip)
            }
        }
    }
    
    private fun getSuggestedTags(): Map<String, String> {
        val suggestions = mutableMapOf<String, String>()
        
        // Based on amenity/shop type, suggest common tags
        val amenity = tags["amenity"]
        val shop = tags["shop"]
        
        when {
            amenity == "fast_food" -> {
                suggestions["takeaway"] = "yes"
                suggestions["drive_through"] = "yes"
                suggestions["outdoor_seating"] = "no"
            }
            amenity == "restaurant" -> {
                suggestions["cuisine"] = ""
                suggestions["outdoor_seating"] = "yes"
                suggestions["reservation"] = "yes"
            }
            amenity == "fuel" -> {
                suggestions["fuel:diesel"] = "yes"
                suggestions["fuel:octane_95"] = "yes"
                suggestions["payment:credit_cards"] = "yes"
            }
            amenity == "bank" || amenity == "atm" -> {
                suggestions["operator"] = ""
                suggestions["wheelchair"] = "yes"
            }
            shop != null -> {
                suggestions["opening_hours"] = ""
                suggestions["phone"] = ""
                suggestions["website"] = ""
            }
        }
        
        // Always suggest these if missing
        if (!tags.containsKey("name") && (amenity != null || shop != null)) {
            suggestions["name"] = ""
        }
        
        return suggestions
    }
    
    private fun showAddTagDialog() {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_tag, null)
        
        val keyEdit = view.findViewById<AutoCompleteTextView>(R.id.keyEdit)
        val valueEdit = view.findViewById<AutoCompleteTextView>(R.id.valueEdit)
        
        // Common keys autocomplete
        val commonKeys = listOf(
            "name", "brand", "cuisine", "opening_hours", "phone", "website",
            "addr:housenumber", "addr:street", "addr:city", "addr:postcode",
            "wheelchair", "outdoor_seating", "takeaway", "drive_through",
            "payment:credit_cards", "payment:cash", "operator"
        )
        keyEdit.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, commonKeys)
        )
        
        AlertDialog.Builder(requireContext())
            .setTitle("Add Tag")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val key = keyEdit.text.toString().trim()
                val value = valueEdit.text.toString().trim()
                if (key.isNotBlank()) {
                    tags[key] = value
                    tagsAdapter?.notifyDataSetChanged()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun confirmEdit() {
        val dialog = dialog as? AlertDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content)?.parent as? ViewGroup ?: return
        
        val descriptionEdit = view.findViewById<EditText>(R.id.descriptionEdit)
        val sideSpinner = view.findViewById<Spinner>(R.id.sideSpinner)
        val distanceAheadEdit = view.findViewById<EditText>(R.id.distanceAheadEdit)
        val distanceSideEdit = view.findViewById<EditText>(R.id.distanceSideEdit)
        
        val newSide = when (sideSpinner?.selectedItemPosition ?: 1) {
            0 -> RelativeSide.LEFT
            1 -> RelativeSide.RIGHT
            2 -> RelativeSide.CENTER
            else -> RelativeSide.RIGHT
        }
        
        val modifiedEdit = edit.copy(
            description = descriptionEdit?.text?.toString() ?: edit.description,
            tags = tags.toMap(),
            relativeSide = newSide,
            distanceAhead = distanceAheadEdit?.text?.toString()?.toDoubleOrNull() ?: edit.distanceAhead,
            distanceSide = distanceSideEdit?.text?.toString()?.toDoubleOrNull() ?: edit.distanceSide
        )
        
        val viewModel = getViewModel<VoiceMapperViewModel>()
        viewModel.modifyEdit(modifiedEdit)
        viewModel.confirmEdit(modifiedEdit)
    }
    
    private fun deleteEdit() {
        val viewModel = getViewModel<VoiceMapperViewModel>()
        viewModel.rejectEdit(edit)
    }
    
    companion object {
        private const val ARG_EDIT = "edit"
        
        fun newInstance(edit: VoiceMapperEdit): VoiceMapperEditDialogFragment {
            return VoiceMapperEditDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_EDIT, edit.toParcelable())
                }
            }
        }
    }
}

/**
 * Simple adapter for displaying editable tags
 */
class TagsAdapter(
    private val tags: MutableMap<String, String>,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<TagsAdapter.ViewHolder>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val key = tags.keys.toList()[position]
        val value = tags[key] ?: ""
        holder.bind(key, value)
    }
    
    override fun getItemCount(): Int = tags.size
    
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val keyText: TextView = itemView.findViewById(R.id.keyText)
        private val valueEdit: EditText = itemView.findViewById(R.id.valueEdit)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        
        fun bind(key: String, value: String) {
            keyText.text = key
            valueEdit.setText(value)
            
            valueEdit.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    tags[key] = valueEdit.text.toString()
                }
            }
            
            deleteButton.setOnClickListener {
                onDelete(key)
            }
        }
    }
}

/**
 * Settings dialog for Voice Mapper
 */
class VoiceMapperSettingsDialogFragment : DialogFragment() {
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_voice_mapper_settings, null)
        
        val viewModel = getViewModel<VoiceMapperViewModel>()
        
        val apiKeyEdit = view.findViewById<EditText>(R.id.apiKeyEdit)
        val audioFeedbackSwitch = view.findViewById<Switch>(R.id.audioFeedbackSwitch)
        val autoConfirmSwitch = view.findViewById<Switch>(R.id.autoConfirmSwitch)
        val defaultSideSpinner = view.findViewById<Spinner>(R.id.defaultSideSpinner)
        val defaultDistanceEdit = view.findViewById<EditText>(R.id.defaultDistanceEdit)

        // Set current values
        apiKeyEdit.setText(de.westnordost.streetcomplete.Prefs.sharedPreferences.getString("anthropic_api_key", "") ?: "")
        audioFeedbackSwitch.isChecked = viewModel.audioFeedbackEnabled.value
        autoConfirmSwitch.isChecked = viewModel.autoConfirmHighConfidence.value

        // Listeners
        audioFeedbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAudioFeedback(isChecked)
        }

        autoConfirmSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoConfirmHighConfidence(isChecked)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Voice Mapper Settings")
            .setView(view)
            .setPositiveButton("Done") { _, _ ->
                val key = apiKeyEdit.text.toString().trim()
                de.westnordost.streetcomplete.Prefs.sharedPreferences.edit()
                    .putString("anthropic_api_key", key)
                    .apply()
            }
            .create()
    }
}

// Extension functions for Parcelable conversion
private fun VoiceMapperEdit.toParcelable(): android.os.Parcelable {
    // In a real implementation, this would be a proper Parcelable
    // For now, we use a simple Bundle approach
    return Bundle().apply {
        putString("id", id)
        putString("type", type.name)
        putString("description", description)
        putSerializable("tags", HashMap(tags))
        putFloat("confidence", confidence)
        putString("side", relativeSide?.name)
        putDouble("distanceAhead", distanceAhead)
        putDouble("distanceSide", distanceSide)
        putString("explanation", aiExplanation)
    }
}

@Suppress("UNCHECKED_CAST")
private fun Bundle.getParcelableCompat(key: String): VoiceMapperEdit? {
    val bundle = this.getBundle(key) ?: return null
    return VoiceMapperEdit(
        id = bundle.getString("id") ?: return null,
        type = EditType.valueOf(bundle.getString("type") ?: return null),
        description = bundle.getString("description") ?: "",
        tags = (bundle.getSerializable("tags") as? HashMap<String, String>)?.toMap() ?: emptyMap(),
        confidence = bundle.getFloat("confidence", 0.8f),
        relativeSide = bundle.getString("side")?.let { RelativeSide.valueOf(it) },
        distanceAhead = bundle.getDouble("distanceAhead", 0.0),
        distanceSide = bundle.getDouble("distanceSide", 15.0),
        aiExplanation = bundle.getString("explanation")
    )
}
