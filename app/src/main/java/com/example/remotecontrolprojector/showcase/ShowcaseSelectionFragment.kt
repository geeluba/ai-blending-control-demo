package com.example.remotecontrolprojector.showcase

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.remotecontrolprojector.R
import com.example.remotecontrolprojector.RemoteControlActivity
import com.example.remotecontrolprojector.databinding.FragmentShowcaseSelectionBinding
import com.example.remotecontrolprojector.remote.RemoteCommand
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ShowcaseSelectionFragment : Fragment() {

    private val TAG = "Projector:ShowcaseSelectionFrag"

    private var _binding: FragmentShowcaseSelectionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ShowcaseSelectionViewModel by viewModels()

    private var leftIp: String? = null
    private var rightIp: String? = null
    private var rightDeviceName: String? = null
    private var rightMacAddress: String? = null

    private var connectionTimeoutJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentShowcaseSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initially disable buttons until connections are confirmed
        binding.btnImageBlending.isEnabled = false
        binding.btnVideoBlending.isEnabled = false

        // Optional: Make them semi-transparent to indicate disabled state visually
        binding.btnImageBlending.alpha = 0.5f
        binding.btnVideoBlending.alpha = 0.5f

        leftIp = arguments?.getString("leftIp")
        rightIp = arguments?.getString("rightIp")
        rightMacAddress = arguments?.getString("rightMacAddress")

        setLoadingState(true, getString(R.string.projectors_pairing_up))
        startConnectionTimeoutTimer()

        setupObservers()
        setupListeners()
    }

    private fun startConnectionTimeoutTimer() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(PAIRING_TIMEOUT_MS) // 10 seconds delay
            if (!viewModel.areProjectorsConnected.value) {
                Log.w(TAG, "Connection timeout reached (10s).")
                viewModel.disconnectClients(0)
                setLoadingState(false)
                showTimeoutDialog()
            }
        }
    }

    private fun showTimeoutDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Connection Timeout")
            .setMessage(getString(R.string.faild_to_connect_two_projectors))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                findNavController().popBackStack()
            }
            .show()
    }

    private fun setLoadingState(isLoading: Boolean, message: String = "") {
        if (_binding == null) return
        binding.loadingOverlay.isVisible = isLoading
        if (isLoading) {
            binding.loadingText.text = message
        }
    }

    private fun setupObservers() {
        val activity = requireActivity() as? RemoteControlActivity

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activity?.serviceFlow?.collectLatest { service ->
                    Log.d(TAG, "Service connected in ShowcaseSelectionFragment")
                    viewModel.setService(service)
                    viewModel.initializeAndConnect(leftIp, rightIp, rightMacAddress)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.areProjectorsConnected.collect { connected ->
                    binding.btnImageBlending.isEnabled = connected
                    binding.btnVideoBlending.isEnabled = connected

                    // Visual feedback
                    val alpha = if (connected) 1.0f else 0.5f
                    binding.btnImageBlending.alpha = alpha
                    binding.btnVideoBlending.alpha = alpha

                    if (connected) {
                        Log.d(TAG, "Both projectors connected, buttons enabled")
                        setLoadingState(false)
                        viewModel.informRemoteClientsBlendingMode(RemoteCommand.BlendingMode.STANDBY)
                    }
                }
            }
        }

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "System back pressed. Sending BlendingMode.NONE to remotes.")

                // remote clients to reset/standby
                if (!viewModel.areProjectorsConnected.value) {
                    viewModel.informRemoteClientsBlendingMode(RemoteCommand.BlendingMode.NONE)
                }

                // Disable this callback so the default behavior (popBackStack) works next
                isEnabled = false

                // Trigger the default back behavior
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun setupListeners() {
        binding.btnImageBlending.setOnClickListener {
            Log.d(TAG, "Image Blending button clicked")
            viewModel.informRemoteClientsBlendingMode(RemoteCommand.BlendingMode.IMAGE)
            findNavController().navigate(R.id.action_showcase_to_image)
        }

        binding.btnVideoBlending.setOnClickListener {
            Log.d(TAG, "Video Blending button clicked")
            viewModel.informRemoteClientsBlendingMode(RemoteCommand.BlendingMode.VIDEO)
            findNavController().navigate(R.id.action_showcase_to_video)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView")
        connectionTimeoutJob?.cancel()
        _binding = null
    }

    companion object {
        const val PAIRING_TIMEOUT_MS = 10000L
    }
}