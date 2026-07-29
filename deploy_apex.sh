#!/usr/bin/env bash
# Builds and installs com.google.android.mediaprovider APEX module
#
# PREREQUISITES:
# source source build/envsetup.sh
# launch <target>
#
# USAGE:
# cd packages/providers/MediaProvider
# ./deploy_apex.sh
set -euo pipefail

echo "BUILDING..."
OVERRIDE_PRODUCT_COMPRESSED_APEX=false \
  $ANDROID_BUILD_TOP/build/soong/soong_ui.bash --make-mode -j64 com.google.android.mediaprovider

echo "INSTALLING..."
adb install $ANDROID_PRODUCT_OUT/system/apex/com.google.android.mediaprovider.apex

echo && echo "REBOOTING..."

adb reboot
adb wait-for-device
echo "Rebooted."

echo && echo "DONE ($SECONDS seconds)"
