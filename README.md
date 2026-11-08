# Smart TV OS (Android TV)

WebView wrapper around the prototype in `app/src/main/assets/index.html`.

## Build the APK
**Android Studio:** File > Open this folder, let it sync (use Gradle 8.7 if asked),
then Build > Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.

**Command line:** with JDK 17, Android SDK 34 and Gradle 8.7: `gradle assembleDebug`

**No local setup:** push this folder to GitHub; the "Build APK" workflow
uploads the APK as a downloadable artifact.

## Install on a TV
Enable Developer options > network debugging on the TV, then:
    adb connect <tv-ip>:5555
    adb install -r app-debug.apk
It appears in the Android TV launcher as "Smart TV OS".

## Notes
- Remote: D-pad = arrow keys, centre = Enter, Back = Escape (Back on Home exits the app).
- The Home button belongs to the system launcher and cannot be intercepted.
- Figtree loads from Google Fonts; offline it falls back to the system font.
- Package name is `com.example.smarttvos`; change it before publishing.
