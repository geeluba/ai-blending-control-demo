package com.example.remotecontrolprojector.pairing

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.remotecontrolprojector.R
import com.example.remotecontrolprojector.RemoteControlActivity
import com.example.remotecontrolprojector.databinding.FragmentPairingBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PairingFragment : Fragment() {

    private val TAG = "Projector:PairingFragment"
    private var _binding: FragmentPairingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PairingViewModel by viewModels()
    private lateinit var deviceAdapter: ScannedDeviceAdapter

    // Hardware State Flag
    private var isHardwareReady = false

    private val enableBtLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // When returning from the system "Turn on Bluetooth" dialog
            checkHardwareAndScan()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupClickListeners()
        setupObservers()

        checkHardwareAndScan()
    }

    @Override
    override fun onStart() {
        super.onStart()
        // Initialize Gatt Client and Start Scanning (if hardware ready)
        if (isHardwareReady) {
            Log.d(TAG, "onStart: Starting Scan")
            viewModel.startScan()
        } else {
            checkHardwareAndScan()
        }
    }

    @Override
    override fun onStop() {
        super.onStop()
        // Stop scanning when app/fragment is in the background
        Log.d(TAG, "onStop: Stopping Scan")
        viewModel.stopScan()
        viewModel.disconnectAllDevices()
    }

    @SuppressLint("MissingPermission")
    private fun setupRecyclerView() {
        deviceAdapter = ScannedDeviceAdapter { scanResult ->
            showSelectionDialog(scanResult.device.address, scanResult.device.name ?: "Unknown")
        }
        binding.deviceListRv.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = deviceAdapter
        }
    }

    private fun setupClickListeners() {
        binding.btnRescan.setOnClickListener {
            if (isHardwareReady) {
                viewModel.stopScan()
                viewModel.startScan()
                Toast.makeText(context, "Rescanning...", Toast.LENGTH_SHORT).show()
            } else {
                checkHardwareAndScan()
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            checkHardwareAndScan()
        }

        binding.btnBlending.setOnClickListener {
            viewModel.stopScan()

            viewModel.requestProjectorWifiIp()
        }
    }

    private fun setupObservers() {
        val activity = requireActivity() as? RemoteControlActivity

        // Wait for Service to be bound (which happens after Activity gets permissions)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activity?.serviceFlow?.collectLatest { service ->
                    viewModel.setService(service)

                    // Auto-start scan if Service is ready AND Hardware is on
                    if (service != null && isHardwareReady) {
                        viewModel.startScan()
                    }
                }
            }
        }

        // UI Observers
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.scanResults.collect { devices ->
                        deviceAdapter.submitList(devices)
                        binding.emptyView.visibility =
                            if (devices.isEmpty()) View.VISIBLE else View.GONE
                        binding.deviceListRv.visibility =
                            if (devices.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.leftDeviceAddress.collect {
                        deviceAdapter.leftDeviceAddress = it
                        deviceAdapter.notifyDataSetChanged()
                    }
                }
                launch {
                    viewModel.rightDeviceAddress.collect {
                        deviceAdapter.rightDeviceAddress = it
                        deviceAdapter.notifyDataSetChanged()
                    }
                }
                launch {
                    viewModel.isLeftRightDeviceSelected.collect { isReady ->
                        binding.btnBlending.visibility = if (isReady) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.navigationEvent.collectLatest { (leftIp, rightIp, rightMac) ->
                        val bundle = Bundle().apply {
                            putString("leftIp", leftIp)
                            putString("rightIp", rightIp)
                            putString("rightMacAddress", rightMac)
                        }
                        //tear down all ble connections, since the remote control will use wifi from now on
                        viewModel.disconnectAllDevices()
                        viewModel.tearDownBle()

                        // Navigate directly to Showcase or Video, passing the IPs
                        Log.d(
                            TAG,
                            "Navigating to Showcase with Left IP: $leftIp, Right IP: $rightIp"
                        )
                        findNavController().navigate(R.id.action_pairing_to_showcase, bundle)
                    }
                }
            }
        }
    }

    private fun showSelectionDialog(address: String, name: String) {
        val currentLeft = viewModel.leftDeviceAddress.value
        val currentRight = viewModel.rightDeviceAddress.value

        if (address == currentLeft || address == currentRight) {
            AlertDialog.Builder(requireContext())
                .setTitle("Connected: $name")
                .setItems(arrayOf("Disconnect Projector")) { _, _ ->
                    viewModel.disconnectDevice(
                        address
                    )
                }
                .show()
        } else {
            val options = arrayOf("Set as LEFT Projector", "Set as RIGHT Projector", "Cancel")
            AlertDialog.Builder(requireContext())
                .setTitle("Manage: $name")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> viewModel.assignLeftProjector(address)
                        1 -> viewModel.assignRightProjector(address)
                    }
                }
                .show()
        }
    }

    // --- Hardware Check Logic (System Settings) ---

    private fun checkHardwareAndScan() {
        val btManager =
            requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter

        if (adapter == null) {
            showErrorOverlay("Bluetooth not supported on this device.")
            return
        }

        if (!adapter.isEnabled) {
            showErrorOverlay("Bluetooth is turned off.")
            // Launch system dialog to turn it on
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (!isLocationEnabled(requireContext())) {
                showErrorOverlay("Location must be enabled for Bluetooth scanning (Android 11 requirement).")
                promptUserToEnableLocation(requireContext())
                return
            }
        }

        hideErrorOverlay()
        isHardwareReady = true
        viewModel.startScan()
    }

    private fun showErrorOverlay(message: String) {
        binding.permissionOverlay.visibility = View.VISIBLE
        binding.scannerLayout.visibility = View.GONE
        // Ideally update the text view in overlay to reflect specific error
        // binding.errorText.text = message
        isHardwareReady = false
    }

    private fun hideErrorOverlay() {
        binding.permissionOverlay.visibility = View.GONE
        binding.scannerLayout.visibility = View.VISIBLE
    }

    private fun promptUserToEnableLocation(context: Context) {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val lm =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else {
            try {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                )
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView")
        viewModel.removeListener()
        viewModel.stopScan()
        _binding = null
    }
}