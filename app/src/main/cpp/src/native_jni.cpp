#include "native_jni.h"


extern "C"
JNIEXPORT jint JNICALL
Java_com_example_remotecontrolprojector_dataOverSound_Ggwave_ggwaveInit(JNIEnv *env, jobject thiz,
                                                                      jint payload_size,
                                                                      jfloat sample_rate,
                                                                      jint sample_format) {
    LOGD("ggwave Init");

    auto defaultParameters = GGWave::getDefaultParameters();
    defaultParameters.payloadLength = payload_size;
    defaultParameters.sampleRateInp = sample_rate;
    defaultParameters.sampleRateOut = sample_rate;
    defaultParameters.sampleFormatInp = static_cast<ggwave_SampleFormat>(sample_format);
    defaultParameters.sampleFormatOut = static_cast<ggwave_SampleFormat>(sample_format);
    p_ggwave = defaultParameters;
    max_payload_size = payload_size;

    LOGD("Operating mode: %d, payload size = %d, sampleRateInp = %f, sampleRateOut = %f, sampleFormatInp = %d, sampleFormatOut = %d",
         defaultParameters.operatingMode,
         defaultParameters.payloadLength, defaultParameters.sampleRateInp,
         defaultParameters.sampleRateOut, defaultParameters.sampleFormatInp,
         defaultParameters.sampleFormatOut);

    g_ggwave = ggwave_init(defaultParameters);

    if (g_ggwave == -1) {
        LOGE("Failed to initialize GGWave instance");
        return -1;
    } else {
        LOGD("GGWave instance initialized with ID: %d", g_ggwave);
    }

    return 0;
}
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_remotecontrolprojector_dataOverSound_Ggwave_ggwaveEncode(JNIEnv *env, jobject thiz,
                                                                        jstring message,
                                                                        jint protocol,
                                                                        jint volume) {
    if (g_ggwave == -1) {
        LOGE("GGWave instance is not initialized");
        return nullptr;
    }

    const char *text = env->GetStringUTFChars(message, nullptr);

    int number = ggwave_encode(g_ggwave, text, env->GetStringLength(message),
                               (ggwave_ProtocolId) protocol, volume,
                               nullptr, 1);


    if (number <= 0) {
        LOGE("GGWave encoding failed to get waveform size");
        env->ReleaseStringUTFChars(message, text);
        return nullptr;
    } else {
        char *waveformBuffer = new char[number];
        int encodedBytes = ggwave_encode(g_ggwave, text, env->GetStringLength(message),
                                         (ggwave_ProtocolId) protocol,
                                         volume, waveformBuffer, 0);
        env->ReleaseStringUTFChars(message, text);
        if (encodedBytes != number) {
            LOGE("Mismatch in encoded bytes: expected %d, got %d", number, encodedBytes);
            delete[] waveformBuffer;
            return nullptr;
        } else {
            LOGD("GGWave encoding successful, encoded bytes: %d", number);
            jbyteArray byteArray = env->NewByteArray(number);
            env->SetByteArrayRegion(byteArray, 0, number,
                                    reinterpret_cast<jbyte *>(waveformBuffer));
            delete[] waveformBuffer;
            return byteArray;
        }
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_remotecontrolprojector_dataOverSound_Ggwave_ggwaveDeinit(JNIEnv *env, jobject thiz) {

    LOGD("GGWave instance; deinitialized");
    ggwave_free(g_ggwave);
    g_ggwave = -1;
    p_ggwave = {};
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_remotecontrolprojector_dataOverSound_Ggwave_ggwaveDecode(JNIEnv *env, jobject thiz,
                                                                  jbyteArray audio_data,
                                                                  jint length) {
    if (g_ggwave == -1) {
        LOGE("GGWave instance is not initialized");
        return nullptr;
    }

    // Get the byte array elements
    jbyte *audioBuffer = env->GetByteArrayElements(audio_data, nullptr);
    if (audioBuffer == nullptr) {
        LOGE("Failed to get byte array elements");
        return nullptr;
    }


    char outputBuffer[max_payload_size];
    memset(outputBuffer, 0, sizeof(outputBuffer));

    // Call ggwave_decode
    // This function returns the number of bytes written to outputBuffer if a message is decoded
    int decodeResult = ggwave_decode(g_ggwave, reinterpret_cast<char *>(audioBuffer), length,
                                     outputBuffer);

    // Release the byte array elements
    env->ReleaseByteArrayElements(audio_data, audioBuffer, JNI_ABORT); // JNI_ABORT = don't copy back

    if (decodeResult > 0) {
        // Message found!
        LOGD("GGWave decoded message: %s", outputBuffer);
        //will be garbage collected by JVM, no need to free
        return env->NewStringUTF(outputBuffer);
    }

    // No message decoded in this chunk
    return nullptr;
}