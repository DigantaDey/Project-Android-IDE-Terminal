LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := forge-pty
LOCAL_SRC_FILES := pty.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror -O2
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
