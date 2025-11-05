package com.example.remotecontrolprojector

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class RemoteControlActivity : AppCompatActivity() {

    private val viewModel: RemoteControlViewModel by viewModels()

    private var isUserScrubbing = false
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_control)

        // Find all UI components
        playPauseButton = findViewById(R.id.play_pause_button)
        seekBar = findViewById(R.id.seek_bar)
        currentTimeTextView = findViewById(R.id.current_time_text)
        totalTimeTextView = findViewById(R.id.total_time_text)
        // connectionStatusText = findViewById(R.id.connection_status_text) // <-- Uncomment if you add it

        // Setup listeners and observers
        setupClickListeners()
        setupObservers()
        setupSeekBar()

        // Attempt to connect
        viewModel.connect()
    }

    private fun setupClickListeners() {
        playPauseButton.setOnClickListener {
            // Read the state from the ViewModel, don't keep local state
            if (viewModel.isPlaying.value) {
                viewModel.sendPause()
            } else {
                viewModel.sendPlay()
            }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            // Observe connection status
            viewModel.isConnected.onEach { isConnected ->
                playPauseButton.isEnabled = isConnected
                seekBar.isEnabled = isConnected
                // connectionStatusText.text = if (isConnected) "Connected" else "Disconnected"
            }.launchIn(this)

            // Observe play/pause state
            viewModel.isPlaying.onEach { isPlaying ->
                playPauseButton.text = if (isPlaying) {
                    getString(R.string.pause)
                } else {
                    getString(R.string.play)
                }
            }.launchIn(this)

            // Observe video duration
            viewModel.durationMs.onEach { duration ->
                if (duration > 0) {
                    seekBar.max = duration.toInt()
                    totalTimeTextView.text = formatMillis(duration)
                }
            }.launchIn(this)

            // Observe playback position
            viewModel.currentPositionMs.onEach { position ->
                if (!isUserScrubbing) {
                    seekBar.progress = position.toInt()
                    currentTimeTextView.text = formatMillis(position)
                }
            }.launchIn(this)
        }
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Update text field as user scrubs
                    currentTimeTextView.text = formatMillis(progress.toLong())
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

    /**
     * Helper function to format milliseconds to mm:ss
     */
    private fun formatMillis(millis: Long): String {
        return String.format(
            "%02d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) -
                    TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        )
    }
}