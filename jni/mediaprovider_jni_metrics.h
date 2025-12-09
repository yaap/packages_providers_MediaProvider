/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef MAIN_MEDIAPROVIDER_JNI_METRICS_H
#define MAIN_MEDIAPROVIDER_JNI_METRICS_H

#include <android/log.h>

#include <chrono>

#include "statslog_mediaprovider.h"

namespace mediaprovider {
namespace fuse {

enum class FuseOpType {
    UNSPECIFIED,
    UNLINK,
    OPENDIR,
    ACCESS,
    RENAME,
    OPEN,
    READ,
    READDIR,
    CREATE,
    GETATTR,
    SETATTR,
    MKDIR,
    RMDIR,
    LOOKUP,
    CANONICAL_PATH
};

enum class VolumeType { UNKNOWN, EXTERNAL_PRIMARY, EXTERNAL_PUBLIC };

class MetricLogger {
  public:
    explicit MetricLogger(FuseOpType fuse_op_type_) {
        auto now = std::chrono::system_clock::now();
        start = std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch());
        fuse_op_type = fuse_op_type_;
        volume = VolumeType::UNKNOWN;
        calling_package_uid = -10;
        log_metric = true;
    }

    explicit MetricLogger(FuseOpType fuse_op_type_, std::string file_path,
                          int calling_package_uid_) {
        auto now = std::chrono::system_clock::now();
        start = std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch());
        fuse_op_type = fuse_op_type_;
        calling_package_uid = calling_package_uid_;
        setVolumeFromPath(file_path);
        log_metric = true;
    }

    explicit MetricLogger(FuseOpType fuse_op_type_, VolumeType volume_, int calling_package_uid_) {
        auto now = std::chrono::system_clock::now();
        start = std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch());
        fuse_op_type = fuse_op_type_;
        volume = volume_;
        calling_package_uid = calling_package_uid_;
        log_metric = true;
    }

    ~MetricLogger() {
        if (log_metric) {
            auto now = std::chrono::system_clock::now();
            auto end = std::chrono::duration_cast<std::chrono::nanoseconds>(now.time_since_epoch());
            long op_execution_time = end.count() - start.count();

            log_fuse_op_reported(op_execution_time);
        }
    }

    void setLogMetric(bool logMetric);

    void setVolume(VolumeType volume);

    void setVolumeFromPath(const std::string& path);

    void setCallingPackageUid(int callingPackageUid);

  private:
    bool log_metric;
    FuseOpType fuse_op_type;
    VolumeType volume;
    int calling_package_uid;
    std::chrono::nanoseconds start;

    int Cast(FuseOpType fuse_op_typeee);
    int Cast(VolumeType volume);

    void log_fuse_op_reported(long op_execution_time);
};
}  // namespace fuse
}  // namespace mediaprovider

#endif  // MAIN_MEDIAPROVIDER_JNI_METRICS_H
