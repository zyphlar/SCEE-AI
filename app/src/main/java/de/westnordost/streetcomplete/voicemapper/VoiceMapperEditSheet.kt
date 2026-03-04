package de.westnordost.streetcomplete.voicemapper

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import org.koin.android.ext.android.inject

/**
 * Unified bottom sheet for viewing and editing a pending voice mapper edit.
 *
 * Used from two contexts:
 *  - Tapping the edit's orange pin on the map (triggered via VoiceMapperService.openEditRequest)
 *  - Tapping "Edit" in the pending-edits list inside VoiceMapperFragment
 *
 * Actions:
 *  - Save          → saves tag/description changes to the pending list, stays pending
 *  - Add to map    → saves changes AND confirms the edit to OSM
 *  - Delete        → removes the edit from the pending list entirely
 */
class VoiceMapperEditSheet : BottomSheetDialogFragment() {

    private val voiceMapperService: VoiceMapperService by inject()

    private var currentEdit: VoiceMapperEdit? = null
    private val editedTags = mutableMapOf<String, String>()

    private var sourceTranscriptionText: TextView? = null
    private var descriptionEdit: EditText? = null
    private var tagsContainer: LinearLayout? = null
    private var positionText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editId = requireArguments().getString(ARG_EDIT_ID) ?: return
        currentEdit = voiceMapperService.pendingEdits.value.find { it.id == editId }
        editedTags.clear()
        currentEdit?.tags?.let { editedTags.putAll(it) }
    }

    override fun onStart() {
        super.onStart()
        val sheet = (dialog as? BottomSheetDialog)
            ?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            // Expand to 85% of screen height so buttons are always visible
            val screenHeight = resources.displayMetrics.heightPixels
            behavior.maxHeight = (screenHeight * 0.85).toInt()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_voice_mapper_edit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val edit = currentEdit ?: return

        sourceTranscriptionText = view.findViewById(R.id.sourceTranscriptionText)
        descriptionEdit = view.findViewById(R.id.descriptionEdit)
        tagsContainer = view.findViewById(R.id.tagsContainer)
        positionText = view.findViewById(R.id.positionText)

        edit.sourceTranscription?.let { transcript ->
            sourceTranscriptionText?.text = "heard: \"$transcript\""
            sourceTranscriptionText?.visibility = View.VISIBLE
        }

        descriptionEdit?.setText(edit.description)
        rebuildTagRows()
        updatePositionDisplay(edit.position)

        view.findViewById<Button>(R.id.addTagButton)?.setOnClickListener { showAddTagDialog() }

        view.findViewById<Button>(R.id.saveButton)?.setOnClickListener {
            saveToService()
            dismiss()
        }

        view.findViewById<Button>(R.id.addToMapButton)?.setOnClickListener {
            saveToService()
            val latest = voiceMapperService.pendingEdits.value.find { it.id == edit.id } ?: return@setOnClickListener
            voiceMapperService.confirmSingleEdit(latest)
            dismiss()
        }

        view.findViewById<Button>(R.id.deleteEditButton)?.setOnClickListener {
            voiceMapperService.removeEdit(edit)
            dismiss()
        }
    }

    private fun rebuildTagRows() {
        val container = tagsContainer ?: return
        container.removeAllViews()
        for ((key, value) in editedTags.toSortedMap()) {
            addTagRow(container, key, value)
        }
    }

    private fun addTagRow(container: LinearLayout, key: String, value: String) {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_tag, container, false)
        val keyText = row.findViewById<TextView>(R.id.keyText)
        val valueEdit = row.findViewById<EditText>(R.id.valueEdit)
        val deleteButton = row.findViewById<ImageButton>(R.id.deleteButton)

        keyText.text = key
        valueEdit.setText(value)
        valueEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) editedTags[key] = valueEdit.text.toString()
        }
        deleteButton.setOnClickListener {
            editedTags.remove(key)
            rebuildTagRows()
        }
        container.addView(row)
    }

    private fun showAddTagDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_tag, null)
        val keyEdit = dialogView.findViewById<EditText>(R.id.keyEdit)
        val valueEdit = dialogView.findViewById<EditText>(R.id.valueEdit)
        AlertDialog.Builder(requireContext())
            .setTitle("Add Tag")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val k = keyEdit.text.toString().trim()
                val v = valueEdit.text.toString().trim()
                if (k.isNotBlank()) {
                    editedTags[k] = v
                    rebuildTagRows()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePositionDisplay(position: LatLon?) {
        positionText?.text = if (position != null) {
            "%.6f, %.6f".format(position.latitude, position.longitude)
        } else {
            "No position"
        }
    }

    private fun saveToService() {
        val edit = currentEdit ?: return
        // Flush any text fields that still have focus
        descriptionEdit?.clearFocus()
        tagsContainer?.let { container ->
            for (i in 0 until container.childCount) {
                container.getChildAt(i)?.findViewById<EditText>(R.id.valueEdit)?.clearFocus()
            }
        }
        val newDescription = descriptionEdit?.text?.toString()?.takeIf { it.isNotBlank() }
            ?: edit.description
        voiceMapperService.modifyEdit(edit.copy(
            description = newDescription,
            tags = editedTags.toMap()
        ))
    }

    companion object {
        private const val ARG_EDIT_ID = "voice_edit_id"

        fun newInstance(editId: String) = VoiceMapperEditSheet().apply {
            arguments = bundleOf(ARG_EDIT_ID to editId)
        }
    }
}
