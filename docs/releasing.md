# Releasing OpenCyvis

How to build and publish a new release APK to GitHub Releases.

## Prerequisites

- Android SDK with Java 17
- `gh` CLI authenticated (`gh auth login`)
- Signing keystore at `android/platform-key/platform.jks`

## Build Release APK

```bash
cd android
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Create a GitHub Release

```bash
# Tag format: v<major>.<minor>.<patch>
VERSION="v1.0.0"

gh release create "$VERSION" \
  android/app/build/outputs/apk/release/app-release.apk \
  --repo opencyvis/opencyvis-phone \
  --title "$VERSION" \
  --notes "Release notes here"
```

## Subsequent Releases

```bash
VERSION="v1.1.0"

cd android && ./gradlew assembleRelease && cd ..

gh release create "$VERSION" \
  android/app/build/outputs/apk/release/app-release.apk \
  --repo opencyvis/opencyvis-phone \
  --title "$VERSION" \
  --notes "- Feature X
- Fix Y"
```

## Notes

- The APK is signed with the project's release key. AOSP users will re-sign it with their own platform key during build (handled by `android_app_import` + `certificate: "platform"` in `Android.bp`).
- Users fetch the APK via `aosp-integration/OpenCyvis/download_prebuilt.sh`, which pulls from the GitHub Release.
- Keep APK size reasonable (currently ~15 MB). If it grows significantly, note it in release notes.

## Versioning

Follow semantic versioning:
- **Major**: breaking changes (API incompatible, requires new AOSP patches)
- **Minor**: new features (backwards compatible)
- **Patch**: bug fixes
