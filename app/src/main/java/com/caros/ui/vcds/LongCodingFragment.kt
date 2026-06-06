package com.caros.ui.vcds

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.caros.databinding.FragmentVcdsLongCodingBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LongCodingFragment : Fragment() {

    private var _binding: FragmentVcdsLongCodingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VCDSViewModel by viewModels({ requireParentFragment() })

    private var editedBytes = ByteArray(8)
    private var currentByteIndex = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentVcdsLongCodingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBytePageSpinner()
        setupCodingStringListener()
        setupReadButton()
        setupSaveButton()
        setupRestoreButton()
    }

    // ── Byte page spinner ─────────────────────────────────────────────────────

    private fun setupBytePageSpinner() {
        val items = (0 until 8).map { "Byte $it" }
        binding.bytePageSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, items
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.bytePageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentByteIndex = pos
                refreshBitEditor()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── Coding string input ───────────────────────────────────────────────────

    private fun setupCodingStringListener() {
        binding.currentCodingString.setOnEditorActionListener { _, _, _ ->
            parseAndLoadCoding(); false
        }
        binding.currentCodingString.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) parseAndLoadCoding()
        }
    }

    private fun parseAndLoadCoding() {
        val raw = binding.currentCodingString.text.toString().trim()
        if (raw.isBlank()) return
        editedBytes = ByteArray(8)
        raw.split(Regex("\\s+")).filter { it.isNotBlank() }.take(8).forEachIndexed { i, hex ->
            runCatching { editedBytes[i] = hex.toInt(16).toByte() }
        }
        refreshBitEditor()
        updatePreview()
    }

    // ── Bit editor grid ───────────────────────────────────────────────────────

    private fun refreshBitEditor() {
        val grid = binding.bitEditorGrid
        grid.removeAllViews()

        val dp = resources.displayMetrics.density
        val rowH = (36 * dp).toInt()
        val byteVal = editedBytes[currentByteIndex].toInt() and 0xFF

        // One row per bit, Bit 7 (MSB) first
        for (bitIndex in 7 downTo 0) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowH
                ).apply { bottomMargin = (2 * dp).toInt() }
                setBackgroundColor(0xFF111111.toInt())
            }

            val capturedBit = bitIndex
            val cb = CheckBox(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                isChecked = (byteVal shr capturedBit) and 1 == 1
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
                gravity = Gravity.CENTER
                setOnCheckedChangeListener { _, checked ->
                    val cur = editedBytes[currentByteIndex].toInt() and 0xFF
                    editedBytes[currentByteIndex] = if (checked) {
                        (cur or (1 shl capturedBit)).toByte()
                    } else {
                        (cur and (1 shl capturedBit).inv()).toByte()
                    }
                    updatePreview()
                }
            }

            val label = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (120 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "Bit $capturedBit"
                setTextColor(0xFF757575.toInt())
                textSize = 11f
                setPadding((8 * dp).toInt(), 0, 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }

            row.addView(cb)
            row.addView(label)
            grid.addView(row)
        }
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    private fun updatePreview() {
        binding.newCodingPreview.text =
            editedBytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupReadButton() {
        binding.btnReadCoding.setOnClickListener {
            Toast.makeText(context, "Čtení long coding — připoj OBD nejprve", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSaveButton() {
        binding.btnSaveCoding.setOnClickListener {
            val preview = binding.newCodingPreview.text.toString()
            if (preview.isBlank() || preview.contains("--")) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Zapsat kódování")
                .setMessage("Nastavit long coding na:\n$preview\n\nTato akce změní nastavení ECU. Pokračovat?")
                .setPositiveButton("Zapsat") { _, _ ->
                    saveCodingBackup()
                    binding.currentCodingString.setText(preview)
                    Toast.makeText(context, "Kódování zapsáno: $preview", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }
    }

    private fun setupRestoreButton() {
        binding.btnRestoreCoding.setOnClickListener {
            val backup = requireContext()
                .getSharedPreferences("vcds_backups", android.content.Context.MODE_PRIVATE)
                .getString("last_coding_backup", null)
            if (backup == null) {
                Toast.makeText(context, "Žádná záloha k dispozici", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Obnovit zálohu")
                .setMessage("Obnovit kódování:\n$backup")
                .setPositiveButton("Obnovit") { _, _ ->
                    binding.currentCodingString.setText(backup)
                    parseAndLoadCoding()
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }
    }

    private fun saveCodingBackup() {
        val current = binding.currentCodingString.text.toString().trim()
        if (current.isNotBlank()) {
            requireContext()
                .getSharedPreferences("vcds_backups", android.content.Context.MODE_PRIVATE)
                .edit().putString("last_coding_backup", current).apply()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
