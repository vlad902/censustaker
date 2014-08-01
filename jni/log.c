#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <android/log.h>

#include "log.h"

static char buf[1024];
void _log(int priority, const char *tag, const char *fmt, ...)
{
    va_list va;

    va_start(va, fmt);
    vsnprintf(buf, sizeof(buf), fmt, va);
    va_end(va);

    __android_log_write(priority, tag, buf);
}

// TODO: Error callback endpoint
void err(const char *error)
{
    LOGE("%s", error);
}
