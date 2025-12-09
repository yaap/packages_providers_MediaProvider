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

#include "mediaprovider_jni_metrics.h"

#include <regex>
#include <string>

#include "libfuse_jni/FuseUtils.h"

namespace mediaprovider {
namespace fuse {

int MetricLogger::Cast(FuseOpType fuse_op_type) {
    switch (fuse_op_type) {
        case FuseOpType::UNSPECIFIED:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__FUSE_OP_UNSPECIFIED;
        case FuseOpType::UNLINK:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__UNLINK;
        case FuseOpType::OPENDIR:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__OPENDIR;
        case FuseOpType::ACCESS:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__ACCESS;
        case FuseOpType::RENAME:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__RENAME;
        case FuseOpType::OPEN:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__OPEN;
        case FuseOpType::READ:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__READ;
        case FuseOpType::READDIR:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__READDIR;
        case FuseOpType::CREATE:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__CREATE;
        case FuseOpType::GETATTR:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__GETATTR;
        case FuseOpType::SETATTR:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__SETATTR;
        case FuseOpType::MKDIR:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__MKDIR;
        case FuseOpType::RMDIR:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__RMDIR;
        case FuseOpType::LOOKUP:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__LOOKUP;
        case FuseOpType::CANONICAL_PATH:
            return mediaprovider::fuse::FUSE_OP_REPORTED__OP_TYPE__CANONICAL_PATH;
    }
}

int MetricLogger::Cast(VolumeType volume_type) {
    switch (volume_type) {
        case VolumeType::UNKNOWN:
            return mediaprovider::fuse::FUSE_OP_REPORTED__VOLUME__UNKNOWN;
        case VolumeType::EXTERNAL_PRIMARY:
            return mediaprovider::fuse::FUSE_OP_REPORTED__VOLUME__EXTERNAL_PRIMARY;
        case VolumeType::EXTERNAL_PUBLIC:
            return mediaprovider::fuse::FUSE_OP_REPORTED__VOLUME__EXTERNAL_OTHER;
    }
}

void MetricLogger::setVolume(VolumeType volume_) {
    volume = volume_;
}

void MetricLogger::setVolumeFromPath(const std::string& path) {
    std::string volumeName = mediaprovider::fuse::getVolumeNameFromPath(path);

    if (volumeName == mediaprovider::fuse::VOLUME_EXTERNAL_PRIMARY) {
        volume = VolumeType::EXTERNAL_PRIMARY;
    } else {
        std::regex volumeRegex(R"(/storage/([a-zA-Z0-9-]+)/)");
        std::smatch match;
        if (std::regex_match(path, match, volumeRegex)) {
            volume = VolumeType::EXTERNAL_PUBLIC;
        } else {
            volume = VolumeType::UNKNOWN;
        }
    }
}

void MetricLogger::setCallingPackageUid(int calling_package_uid_) {
    calling_package_uid = calling_package_uid_;
}

void MetricLogger::setLogMetric(bool logMetric) {
    log_metric = logMetric;
}

/**
 * Log fuse op metrics. Sample logging such that metrics are logged only once every 100ms ticks.
 * @param op_execution_time
 */
void MetricLogger::log_fuse_op_reported(long op_execution_time) {
    auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                         std::chrono::steady_clock::now().time_since_epoch())
                         .count();

    if (nowMs % 100 == 0) {
        mediaprovider::fuse::stats_write(mediaprovider::fuse::FUSE_OP_REPORTED, Cast(fuse_op_type),
                                         Cast(volume), calling_package_uid, op_execution_time);
    }
}
}  // namespace fuse
}  // namespace mediaprovider