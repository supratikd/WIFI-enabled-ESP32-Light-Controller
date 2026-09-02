ESP32 Relay Controller - Android Studio Project (Jetpack Compose)

Instructions:
1. Download and unzip this project.
2. Open the folder with Android Studio (File -> Open).
3. Let Android Studio sync/upgrade Gradle as prompted (it will download required tools).
4. Build & Run on an Android device (or emulator with network access to your ESP32).

Notes:
- The app uses Kotlin + Jetpack Compose and a simple TCP socket to send plain-text commands.
- Default IP is 192.168.4.1 and port 8080; change to your ESP32 IP.
- If Android Studio prompts to upgrade Gradle/Kotlin plugin, accept recommended versions.
