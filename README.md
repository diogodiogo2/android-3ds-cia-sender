# CIA Sender

A tiny Android app that installs `.cia` files on a Nintendo 3DS over Wi-Fi, using [FBI](https://github.com/Steveice10/FBI)'s *Remote Install > Receive URLs over the network*.
It is the Android counterpart of the desktop tool BOOP.

- No dependencies, no native code (works on 32-bit and 64-bit devices, ARM and x86).
- Small APK; runs on Android 2.3 (API 9) and newer.
- Supports both English and Portuguese.

## AI Disclaimer

This whole project (and by that I mean everything, even this readme) has been written by AI, more specifically Claude. I don't even know how to program in Java.

It's just a simple app I've built for personal use, and decided to share as I quite liked the result. It hasn't been extensively tested and I may not be able to help with all the issues (be free to document them though, I'll do my best).

I understand many people don't like AI code, and I get their reasoning. You're completely free to avoid using this project. It's just a quick project I wanted to share.

While I'm quite certain this can't do any harm to your Android device (you can even use it without giving full storage access on recent Android versions) and much less to your 3DS (it just receives a download URL through the network), the program comes with NO WARRANTY whatsoever. You agree to take that risk.

## Usage

1. On the 3DS, open FBI and go to **Remote Install > Receive URLs over the network**. Note the 3DS IP address.
2. Connect the Android device to the **same Wi-Fi network** as the 3DS (make sure your Wi-Fi doesn't have some sort of AP isolation. That may block local access).
3. Open CIA Sender and type the 3DS IP address.
4. Choose what to send:
   - **Select file**: type a path or tap **Browse** to use the system file picker.
   - **Select folder**: type a path (or tap **Browse** on Android 5.0+). The `.cia` files in the
     folder are listed; check the ones you want.
5. Tap **Send to 3DS** and keep the app open until the 3DS finishes downloading.

The transfer speed is usually limited by the quite slow 3DS Wi-Fi. On my Old 3DS, it gets around 300kb/s.

## How it works

FBI listens on TCP port 5000 and accepts a list of URLs. The app:

1. starts a small HTTP server (port 8080, GET/HEAD with Range support) that serves **only** the files you chose.
2. connects to the 3DS and sends a 4-byte big-endian length followed by the URLs, one per line.

The 3DS then downloads the files from the device by itself.

## Storage access

- Files and folders chosen with **Browse** go through Android's Storage Access Framework, so no storage permission is needed for them. Keep in mind:

      * This is not available for older Android versions (I believe it's Android 5.0+).
      
      * On Android 11+, the system does not let you pick some folders, like `Download` or the root of the internal storage. You can pick a sub-folder, manually type the path, or choose a single file.
      
- Typed paths use plain file access and need the storage permission (the app asks for it).

## Building

Requirements: JDK 8 or newer, the Android SDK (build-tools and one platform, API 23+), and `zip`.

```
ANDROID_HOME=$HOME/android-sdk ./build.sh
```

This produces `cia-sender.apk`, signed with a debug key.

- **Debian/Ubuntu packages:** `./build.sh` also finds the tools installed by
  `sudo apt install android-sdk aapt zipalign apksigner dalvik-exchange default-jdk-headless zip`.
- **Without installing anything:** the workflow in `.github/workflows/build.yml` builds the APK on
  GitHub Actions (Actions > Build APK > Run workflow).
- You can point at a specific `android.jar` with `ANDROID_JAR=/path/to/android.jar ./build.sh`.

## Tests

The protocol, HTTP server, IP validation and translations are plain Java and can be tested without Android:

```
mkdir -p /tmp/t
javac -encoding UTF-8 -d /tmp/t src/br/local/ciasender/{Protocol,FileServer,FileSource,Msg}.java \
      test/br/local/ciasender/TestAll.java
java -cp /tmp/t br.local.ciasender.TestAll
```

## Adding a language

All texts are in `src/br/local/ciasender/Msg.java`. Add a field to each constant and extend `Msg.get`.
