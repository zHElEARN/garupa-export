# Garupa Export

Kotlin Multiplatform project with native Android and iOS user interfaces.

- `androidApp/`: Android app, using Kotlin and Jetpack Compose.
- `iosApp/`: iOS app, using Swift and SwiftUI.
- `sharedLogic/`: shared Kotlin code for both apps.

Build the Android app with `./gradlew :androidApp:assembleDebug`. Open
`iosApp/iosApp.xcodeproj` in Xcode to build and run the iOS app. The Gradle
wrapper uses JDK 21; iOS builds also require Xcode and its command-line tools.
