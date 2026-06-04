package com.caros.ui.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.caros.audio.AudioProfile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AudioProfilesFragment : Fragment() {
    private val viewModel: AudioViewModel by viewModels({ requireParentFragment() })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            setBackgroundColor(0xFF080808.toInt())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val layout = view as LinearLayout
        AudioProfile.ALL.forEach { profile ->
            Button(requireContext()).apply {
                text = profile.name
                setBackgroundColor(0xFF111111.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dp).also { it.setMargins(0, 4.dp, 0, 4.dp) }
                setOnClickListener { viewModel.applyProfile(profile) }
            }.also { layout.addView(it) }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
