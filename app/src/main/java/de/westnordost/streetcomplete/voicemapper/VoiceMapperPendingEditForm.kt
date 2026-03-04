package de.westnordost.streetcomplete.voicemapper

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.overlays.AbstractOverlayForm
import de.westnordost.streetcomplete.overlays.AnswerItem
import de.westnordost.streetcomplete.overlays.AnswerItem2
import de.westnordost.streetcomplete.overlays.IAnswerItem
import org.koin.android.ext.android.inject

/**
 * Overlay form for viewing and editing a pending voice mapper edit.
 *
 * Opened when the user taps on a pending-edit pin on the map.
 * The edit is stored only in [VoiceMapperService] until confirmed to OSM.
 */
class VoiceMapperPendingEditForm : AbstractOverlayForm() {

    override val contentLayoutResId = R.layout.fragment_voice_mapper_pending_edit

    private val voiceMapperService: VoiceMapperService by inject()

    private var currentEdit: VoiceMapperEdit? = null
    private val editedTags = mutableMapOf<String, String>()
    private var descriptionEdit: EditText? = null
    private var tagsContainer: LinearLayout? = null
    private var positionText: TextView? = null
    private var moveModeHint: TextView? = null
    private var isMoveMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editId = requireArguments().getString(ARG_EDIT_ID) ?: return
        currentEdit = voiceMapperService.pendingEdits.value.find { it.id == editId }
        editedTags.clear()
        currentEdit?.tags?.let { editedTags.putAll(it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val edit = currentEdit ?: return
        setTitleHintLabel(edit.description)

        descriptionEdit = view.findViewById(R.id.descriptionEdit)
        tagsContainer = view.findViewById(R.id.tagsContainer)
        positionText = view.findViewById(R.id.positionText)
        moveModeHint = view.findViewById(R.id.moveModeHint)
        val addTagButton = view.findViewById<Button>(R.id.addTagButton)
        val movePositionButton = view.findViewById<Button>(R.id.movePositionButton)

        descriptionEdit?.setText(edit.description)
        rebuildTagRows()
        updatePositionDisplay(edit.position)

        addTagButton?.setOnClickListener { showAddTagDialog() }
        movePositionButton?.setOnClickListener { toggleMoveMode() }
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

    private fun toggleMoveMode() {
        isMoveMode = !isMoveMode
        moveModeHint?.visibility = if (isMoveMode) View.VISIBLE else View.GONE
    }

    private fun updatePositionDisplay(position: LatLon?) {
        if (position != null) {
            positionText?.text = "%.6f, %.6f".format(position.latitude, position.longitude)
        } else {
            positionText?.text = "No position"
        }
    }

    override fun onClickMapAt(position: LatLon, clickAreaSizeInMeters: Double): Boolean {
        if (!isMoveMode) return false
        val edit = currentEdit ?: return false
        val updated = edit.copy(position = position)
        voiceMapperService.modifyEdit(updated)
        currentEdit = updated
        updatePositionDisplay(position)
        isMoveMode = false
        moveModeHint?.visibility = View.GONE
        return true
    }

    override val otherAnswers: List<IAnswerItem> get() {
        val edit = currentEdit ?: return emptyList()
        return listOf(
            AnswerItem2("Add to map now") { confirmToOsm(edit) },
            // Using quest_generic_answer_does_not_exist prevents AbstractOverlayForm's default delete answer
            AnswerItem(R.string.quest_generic_answer_does_not_exist) { rejectEdit(edit) }
        )
    }

    // Always treated as having changes so the OK button stays enabled
    override fun hasChanges(): Boolean = true

    override fun isFormComplete(): Boolean = true

    // Auto-save on close so the user never loses tag edits regardless of how they dismiss
    override fun onClickClose(onConfirmed: () -> Unit) {
        saveEditToService()
        onConfirmed()
    }

    override fun onClickOk() {
        saveEditToService()
        closeForm()
    }

    private fun saveEditToService() {
        val edit = currentEdit ?: return
        // Flush any focused text field
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

    private fun confirmToOsm(edit: VoiceMapperEdit) {
        saveEditToService()
        voiceMapperService.confirmSingleEdit(voiceMapperService.pendingEdits.value.find { it.id == edit.id } ?: edit)
        closeForm()
    }

    private fun rejectEdit(edit: VoiceMapperEdit) {
        voiceMapperService.removeEdit(edit)
        closeForm()
    }

    private fun closeForm() {
        val listener = parentFragment as? AbstractOverlayForm.Listener
            ?: activity as? AbstractOverlayForm.Listener
        listener?.onEdited(overlay, geometry)
    }

    companion object {
        private const val ARG_EDIT_ID = "voice_edit_id"

        fun newInstance(editId: String): VoiceMapperPendingEditForm =
            VoiceMapperPendingEditForm().apply {
                arguments = bundleOf(ARG_EDIT_ID to editId)
            }
    }
}
