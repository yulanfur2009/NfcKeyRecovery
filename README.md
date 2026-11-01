# NfcKeyRecovery

NfcKeyRecovery is an offline-first Android application for inspecting,
backing up, and recovering keys for MIFARE Classic cards that you own or
are explicitly authorized to test.

## Current features

- Android 8.0+ and Jetpack Compose
- NFC ReaderMode with tag technology, UID, ATQA and SAK inspection
- MIFARE Classic Mini/1K/2K/4K sector layout support
- Key A/Key B candidate authentication and per-sector key mapping
- Authorized card dump reading with trailer/access-bit decoding
- `.keys`, `.txt`, `.mfd`, raw binary and JSON import/export codecs
- Versioned HTTPS/GitHub Raw key feeds with SHA-256 tracking
- USB device inventory and a Proxmark3 adapter boundary
- Chinese and English-ready UI structure

## Safety model

The bundled feed contains only public factory/default/test keys. The app
does not ship operational access-system credentials. Destructive writes
require a backup and explicit confirmation. Manufacturer-block writes are
disabled in the Android NFC backend.

## Build

Requirements:

- JDK 17
- Android SDK 36
- Android build tools 36.x

```shell
./gradlew assembleDebug
```

## Hardware recovery

Android's public NFC API can authenticate candidate keys but does not expose
all low-level exchanges required by nested/hardnested recovery. The project
therefore separates card operations from external hardware adapters. A
Proxmark3 adapter can be completed and device-tested after selecting hardware.

## License

GPL-3.0-or-later.
