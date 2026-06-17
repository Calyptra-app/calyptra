# R8 / ProGuard keep rules for the release build.
#
# Referenced by app/build.gradle.kts:
#   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
#
# Intentionally empty: the app relies on R8 defaults plus the optimized
# Android config. v1.3.0 shipped minified on defaults and was smoke-tested
# on-device. Add -keep rules here if reflection/serialization needs arise,
# and re-verify any minified release on a real device.
