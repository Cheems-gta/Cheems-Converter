# Build an Android APK from your phone

1. Open the repository's Actions tab.
2. Select "Build Android APK" and choose "Run workflow" on the main branch.
3. Wait for the green check mark.
4. Open the workflow run and download the artifact called CheemsConverter-debug-APK.
5. Unzip it on your phone and install app-debug.apk. Android may prompt for permission to install apps from the browser or file manager.

This is an unsigned-for-release debug APK, automatically signed with the Android debug key. Do not distribute it as a final trusted release.

App requires Android 10 or later (min SDK 29). The full GPL FFmpegKit dependency is not suitable for closed-source distribution without appropriate license compliance.
