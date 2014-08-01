#pragma once

#include <android/log.h>

#define TAG       "census"
#define LOGI(...) _log(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) _log(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) _log(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

void _log(int priority, const char *tag, const char *fmt, ...);
void err(const char *error);
