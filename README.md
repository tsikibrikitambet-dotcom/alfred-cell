# Alfred Cell

Alfred Cell is an offline Android app created by **Tambet Sarapu**. Alfred is
my cat. The app shows GPS coordinates, a UTC clock, the latest GPS-fix time,
and cellular signal information reported by the phone. The red radar displays
signal strength, not tower distance or direction. Nearby cell details depend
on the Android device and mobile carrier.

The app has no Internet permission and makes no network requests. GPS works
without mobile data; the first satellite fix can take longer indoors.

## Install on an Android phone

1. Download [alfred-cell.apk from the v1.12 release](https://github.com/tsikibrikitambet-dotcom/alfred-cell/releases/tag/v1.12)
	to your phone. You can also transfer it by USB or nearby file sharing.
2. Open the APK from the phone's **Files** or **Downloads** app.
3. If Android blocks the installation, open **Settings** from the prompt and
	allow **Install unknown apps** for the app used to open the APK. Return to
	the APK and tap **Install**.
4. Open Alfred Cell and allow **Precise location** and **Phone** permissions.
5. Turn on Location and keep the SIM/mobile service active for cell readings.
	For a faster GPS fix, go outdoors with a clear view of the sky. Internet and
	mobile data are not required.

To install an update over an existing copy, it must use the same signing key.
Debug APKs built by this project use the local Android debug key.

## Build

Open this folder in Android Studio and wait for Gradle sync. Or run
`gradle assembleDebug` here with Gradle and the Android SDK installed. Gradle
creates `app/build/outputs/apk/debug/app-debug.apk`; rename it to
`alfred-cell.apk` before transferring it to the phone.

## Permissions

- `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`: GPS coordinates and
  Android's cell-information access rules.
- `READ_PHONE_STATE`: registered and nearby cell information.

There is deliberately no `INTERNET` permission.