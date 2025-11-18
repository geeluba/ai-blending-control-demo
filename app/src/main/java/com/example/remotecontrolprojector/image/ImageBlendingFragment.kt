package com.example.remotecontrolprojector.image

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.remotecontrolprojector.R
import com.example.remotecontrolprojector.RemoteControlActivity
import com.example.remotecontrolprojector.databinding.FragmentImageBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ImageBlendingFragment : Fragment() {

    private val TAG = "Projector:ImageFragment"
    private var _binding: FragmentImageBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImageBlendingViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        setupObservers()
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
        (activity as? RemoteControlActivity)?.serviceFlow
            ?.onEach { service ->
                if (service != null) {
                    Log.d(TAG, "Service attached to ImageFragment")
                    viewModel.setService(service)
                }
            }?.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.isControlsEnabled.onEach { isConnected ->
            binding.playPauseButton.isEnabled = isConnected
            binding.root.alpha = if (isConnected) 1.0f else 0.5f
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.isPlaying.onEach { isPlaying ->
            binding.playPauseButton.text = if (isPlaying) {
                getString(R.string.pause)
            } else {
                getString(R.string.play)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }
}