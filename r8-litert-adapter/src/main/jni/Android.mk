LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := r8_preprocess
LOCAL_SRC_FILES := r8_bitmap_preprocessor.cpp
LOCAL_CPPFLAGS := -std=c++17 -O3 -ffast-math -Wall -Wextra -Werror
LOCAL_LDLIBS := -ljnigraphics -llog
include $(BUILD_SHARED_LIBRARY)
