LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE    := censustaker

SRC_FILES_      := $(wildcard $(LOCAL_PATH)/*.c)
LOCAL_SRC_FILES := $(SRC_FILES_:$(LOCAL_PATH)/%=%)

LOCAL_LDLIBS    := -llog

LOCAL_CFLAGS    := -W -Wall -Wextra -Wno-unused-parameter -Werror
include $(BUILD_SHARED_LIBRARY)
