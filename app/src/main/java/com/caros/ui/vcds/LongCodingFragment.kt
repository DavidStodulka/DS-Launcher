package com.caros.ui.vcds

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.caros.databinding.FragmentVcdsLongCodingBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LongCodingFragment : Fragment() {
    private var _binding: FragmentVcdsLongCodingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: VCDSViewModel by viewModels({ requireParentFragment() })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentVcdsLongCodingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnReadCoding.setOnClickListener {
            Toast.makeText(context, "Reading long coding — connect OBD first", Toast.LENGTH_SHORT).show()
        }
        binding.btnSaveCoding.setOnClickListener {
            val newCoding = binding.etCodingString.text.toString()
            if (newCoding.isBlank()) return@setOnClickListener
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Uložit kódování")
                .setMessage("Nastavit long coding na:\n$newCoding\n\nPokračovat?")
                .setPositiveButton("Uložit") { _, _ ->
                    Toast.makeText(context, "Coding saved (not yet implemented)", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Zrušit", null).show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
