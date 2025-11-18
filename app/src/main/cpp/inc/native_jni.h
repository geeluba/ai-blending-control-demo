#ifndef NATIVE_JNI_H
#define NATIVE_JNI_H

#include <jni.h>
#include <string.h>
#include "common.h"
#include "ggwave/ggwave.h"

ggwave_Instance g_ggwave = -1;
ggwave_Parameters p_ggwave = {0};

int max_payload_size = 20;

#endif