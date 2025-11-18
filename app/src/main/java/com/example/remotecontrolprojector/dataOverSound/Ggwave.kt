package com.example.remotecontrolprojector.dataOverSound

import android.util.Log

class Ggwave() {

    private val TAG = "Projector:Ggwave"

    fun getSupportedFormat(): IntArray {
        return intArrayOf(GGWAVE_SAMPLE_FORMAT_I16)
    }

    fun init(
        payloadLength: Int = max_payload_length,
        sampleRate: Int = GGWAVE_SAMPLE_RATE_48000,
        sampleFormat: Int = GGWAVE_SAMPLE_FORMAT_I16,
    ) {
        if (ggwaveInit(payloadLength, sampleRate.toFloat(), sampleFormat) != 0) {
            Log.e(TAG, "Ggwave initialization failed.")
        }
    }

    fun deinit() {
        if (ggwaveDeinit() != 0) {
            Log.e(TAG, "Ggwave de-initialization failed.")
        }
    }

    fun encode(
        message: String,
        protocol: Int = GGWAVE_PROTOCOL_ULTRASOUND_FASTEST,
        volume: Int,
    ): ByteArray? {
        return ggwaveEncode(message, protocol, volume)
    }

    fun decode(audioData: ByteArray, length: Int): String? {
        return ggwaveDecode(audioData, length)
    }

    external fun ggwaveInit(payloadLength: Int, sampleRate: Float, sampleFormat: Int): Int

    external fun ggwaveDeinit(): Int

    external fun ggwaveEncode(message: String, protocol: Int, volume: Int): ByteArray?

    external fun ggwaveDecode(audioData: ByteArray, length: Int): String?


    companion object {

        init {
            try {
                System.loadLibrary("native-lib")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }

        val max_payload_length = 256

        //specified in ggwave.h
        //sound protocols
        val GGWAVE_PROTOCOL_AUDIBLE_NORMAL = 0
        val GGWAVE_PROTOCOL_AUDIBLE_FAST = 1
        val GGWAVE_PROTOCOL_AUDIBLE_FASTEST = 2
        val GGWAVE_PROTOCOL_ULTRASOUND_NORMAL = 3
        val GGWAVE_PROTOCOL_ULTRASOUND_FAST = 4
        val GGWAVE_PROTOCOL_ULTRASOUND_FASTEST = 5

        //sound format
        val GGWAVE_SAMPLE_FORMAT_UNDEFINED = 0
        val GGWAVE_SAMPLE_FORMAT_U8 = 1
        val GGWAVE_SAMPLE_FORMAT_I8 = 2
        val GGWAVE_SAMPLE_FORMAT_U16 = 3
        val GGWAVE_SAMPLE_FORMAT_I16 = 4
        val GGWAVE_SAMPLE_FORMAT_F32 = 5

        //sampling rate
        val GGWAVE_SAMPLE_RATE_44100 = 44100
        val GGWAVE_SAMPLE_RATE_48000 = 48000
    }
}