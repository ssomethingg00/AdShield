# AdShield — Android Ad Blocker

A no-root Android ad blocker for short-form drama/video apps like DramaBox, ReelShort, FreereelsTV, StoryTV, NetShort, and more.

## How it Works
Uses Android's `VpnService` API to create a local DNS sinkhole — no root required, no data leaves your device.

## Build
```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

## Test
```bash
./gradlew test               # Unit tests
./gradlew connectedAndroidTest  # Instrumented tests
```

## Distribution
Distributed via GitHub Releases (not Play Store — policy restriction on system-wide ad blockers).
