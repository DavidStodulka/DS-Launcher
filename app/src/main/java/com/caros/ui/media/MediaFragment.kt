package com.caros.ui.media

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.caros.audio.MediaSessionController
import com.caros.databinding.FragmentMediaBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MediaFragment : Fragment() {

    private var _binding: FragmentMediaBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var mediaController: MediaSessionController

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mediaController.initialize()
        observeMedia()
        setupControls()
    }

    private fun observeMedia() {
        viewLifecycleOwner.lifecycleScope.launch {
            mediaController.mediaInfo.collect { info ->
                binding.tvTitle.text = info.title.ifEmpty { "Žádné přehrávání" }
                binding.tvArtist.text = info.artist

                // Album art
                if (!info.albumArtUri.isNullOrEmpty()) {
                    try {
                        binding.ivAlbumArt.setImageURI(Uri.parse(info.albumArtUri))
                    } catch (e: Exception) {
                        binding.ivAlbumArt.setImageResource(android.R.color.darker_gray)
                    }
                } else {
                    binding.ivAlbumArt.setImageResource(android.R.color.darker_gray)
                }

                // SeekBar / duration
                if (info.durationMs > 0) {
                    binding.seekBar.max = info.durationMs.toInt()
                    binding.seekBar.progress = info.positionMs.toInt()
                    binding.tvElapsed.text = formatTime(info.positionMs)
                    binding.tvDuration.text = formatTime(info.durationMs)
                }

                // Play/Pause button state
                binding.btnPlay.text = if (info.isPlaying) "⏸" else "▶"
            }
        }
    }

    private fun setupControls() {
        binding.btnPlay.setOnClickListener {
            if (mediaController.mediaInfo.value.isPlaying) {
                mediaController.pause()
            } else {
                mediaController.play()
            }
        }

        binding.btnNext.setOnClickListener { mediaController.next() }
        binding.btnPrev.setOnClickListener { mediaController.previous() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvElapsed.text = formatTime(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                mediaController.seekTo(sb?.progress?.toLong() ?: 0L)
            }
        })

        // Source buttons — visual highlight only
        binding.btnSourceBT.setOnClickListener { highlightSource(binding.btnSourceBT) }
        binding.btnSourceUSB.setOnClickListener { highlightSource(binding.btnSourceUSB) }
        binding.btnSourceFM.setOnClickListener { highlightSource(binding.btnSourceFM) }
    }

    private fun highlightSource(selected: android.widget.Button) {
        listOf(binding.btnSourceBT, binding.btnSourceUSB, binding.btnSourceFM).forEach {
            it.alpha = if (it === selected) 1.0f else 0.4f
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1_000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mediaController.release()
        _binding = null
    }
}
