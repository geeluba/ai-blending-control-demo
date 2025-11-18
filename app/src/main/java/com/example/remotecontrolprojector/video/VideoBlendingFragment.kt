package com.example.remotecontrolprojector.video

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.remotecontrolprojector.R
import com.example.remotecontrolprojector.RemoteControlActivity
import com.example.remotecontrolprojector.databinding.FragmentVideoBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.TimeUnit

class VideoBlendingFragment : Fragment() {

    private val TAG = "Projector:VideoFragment"
    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!

    // --- Get the SHARED ViewModel from the Activity ---
    private val viewModel: VideoBlendingViewModel by viewModels()

    private var isUserScrubbing = false


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentVideoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        setupObservers()
        setupSeekBar()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView")
        _binding = null
    }

    private fun setupClickListeners() {
        binding.playPauseButton.setOnClickListener {
            if (viewModel.isPlaying.value) {
                viewModel.sendPause()
            } else {
                viewModel.sendPlay()
            }
        }
    }

    private fun setupObservers() {
        // Observe connection status (from the LEFT client)
        viewModel.isControlsEnabled.onEach { isConnected ->
            binding.playPauseButton.isEnabled = isConnected
            binding.seekBar.isEnabled = isConnected
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Observe play/pause state
        viewModel.isPlaying.onEach { isPlaying ->
            binding.playPauseButton.text = if (isPlaying) {
                getString(R.string.pause)
            } else {
                getString(R.string.play)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Observe video duration
        viewModel.durationMs.onEach { duration ->
            if (duration > 0) {
                binding.seekBar.max = duration.toInt()
                binding.totalTimeText.text = formatMillis(duration)
            } else {
                binding.seekBar.max = 0
                binding.totalTimeText.text = formatMillis(0)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        // Observe playback position
        viewModel.currentPositionMs.onEach { position ->
            if (!isUserScrubbing) {
                binding.seekBar.progress = position.toInt()
                binding.currentTimeText.text = formatMillis(position)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        (activity as? RemoteControlActivity)?.serviceFlow
            ?.onEach { service ->
                if (service != null) {
                    viewModel.setService(service)
                }
            }?.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.currentTimeText.text = formatMillis(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserScrubbing = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserScrubbing = false
                seekBar?.progress?.let { position ->
                    viewModel.sendSeek(position.toLong())
                }
            }
        })
    }

    private fun formatMillis(millis: Long): String {
        return String.format(
            "%02d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) -
                    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        )
    }
}