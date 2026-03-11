# AndroidNetworkHub

**An offline LAN hotspot and FTP-based Network Attached Storage server for the Samsung Galaxy S8+ running Android 9.0 Pie.**

Transforms the device into a standalone Wi-Fi access point and FTP server, enabling file transfer to and from the device's SD card over a locally isolated network — no internet connection required.

---

## Architecture Overview

The application follows Clean Architecture with three strictly separated layers:

- **UI Layer** — Jetpack Compose, reactive state observation via `StateFlow`
- **Domain Layer** — Pure Kotlin use cases, framework-independent business logic
- **Data Layer** — Android platform adapters (WifiManager, NsdManager, SAF, MinimalFTP)

Dependency injection is provided by **Hilt**. All long-running operations execute under a **Foreground Service** with a `PARTIAL_WAKE_LOCK` to prevent CPU suspension during file transfer.

---

## Build Requirements

| Requirement | Version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 |
| Gradle | 8.1.2 |
| Kotlin | 1.9.10 |
| `minSdk` | 26 (Android 8.0) |
| `targetSdk` | 28 (Android 9.0) |

---

## Building the Project

```bash
# Clone the repository
git clone <repo-url>
cd AndroidNetworkHub

# Use JDK 17 for Gradle/AGP
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"

# Point to your Android SDK (or create local.properties manually)
export ANDROID_HOME=$HOME/Android/Sdk

# Build a debug APK
./gradlew assembleDebug

# Install directly to a connected device
./gradlew installDebug
```

---

## First-Time Setup on the Galaxy S8+

**Step 1 — Grant Permissions.** On first launch, the application will request Location permission. This is mandatory on Android 9 for the `startLocalOnlyHotspot()` API; the OS requires it to prevent covert network topology scanning.

**Step 2 — Grant SD Card Access.** Tap **"Grant SD Card Access"** and navigate to the root of your microSD card in the system picker. Tap **"Allow"** or **"Use this folder"**. This grants the application SAF tree-level access, which persists across reboots. Without this step, the FTP server will only serve the application's internal storage directory.

**Step 3 — Format the SD card as exFAT.** FAT32 imposes a 4 GB per-file limit. For NAS use cases involving video files or archives exceeding this size, the SD card must be formatted as exFAT in the device's Storage Settings.

**Step 4 — Start the Hub.** Tap **"Start Network Hub"**. The system generates a random SSID and WPA2 password and displays them on-screen alongside a scannable QR code. The FTP server binds to port 2121 on the hotspot gateway (`192.168.43.1`).

---

## Connecting a Client

**From Windows:**
1. Scan the QR code or manually connect to the displayed Wi-Fi network.
2. Open FileZilla and connect to `ftp://192.168.43.1:2121` (anonymous login).
3. The Galaxy S8+ SD card is now accessible as a network drive.

**From macOS / Linux:**
- Use any FTP client (e.g. Cyberduck, lftp) pointing to `ftp://192.168.43.1:2121`.
- Alternatively, the server will appear in the **Network** panel of Finder / Nautilus via mDNS discovery once the device is joined to the hotspot.

---

## Key Technical Constraints

**SSID and Password are system-generated.** The `startLocalOnlyHotspot()` API on Android 9 does not permit custom credentials. Each session generates a new random SSID and password. The QR code on-screen provides frictionless rejoining without manual re-entry.

**2.4 GHz band likely on Exynos variants.** The Exynos 8895 chipset in many Galaxy S8+ models locks the hotspot to 2.4 GHz. 5 GHz is available only on Snapdragon variants where carrier firmware permits it. The application accommodates both configurations.

**SD card must use exFAT for files > 4 GB.** FAT32 enforces a 4 GB per-file limit. This is a filesystem constraint, not an application limitation.

---

## Project Structure

```
AndroidNetworkHub/
├── app/src/main/
│   ├── AndroidManifest.xml          # Permissions and service declarations
│   └── java/com/example/networkhub/
│       ├── App.kt                   # Hilt application class + notification channel
│       ├── di/                      # Hilt DI modules
│       │   ├── NetworkModule.kt     # WifiManager, NsdManager singletons
│       │   └── ServerAndStorageModules.kt
│       ├── domain/
│       │   ├── models/
│       │   │   ├── HotspotInfo.kt   # SSID, password, QR string
│       │   │   └── ServerStatus.kt  # Sealed lifecycle state machine
│       │   └── usecases/
│       │       ├── StartHotspotUseCase.kt
│       │       ├── StartFtpServerUseCase.kt
│       │       └── ManageWakeLockUseCase.kt
│       ├── data/
│       │   ├── network/
│       │   │   ├── HotspotManagerImpl.kt   # callbackFlow wrapping LocalOnlyHotspot
│       │   │   └── NsdBroadcaster.kt       # mDNS + MulticastLock
│       │   └── storage/
│       │       ├── SafStorageBridge.kt     # SAF URI persistence
│       │       └── FtpVirtualFileSystem.kt # IFileSystem<DocumentFile> impl
│       ├── service/
│       │   ├── HubForegroundService.kt     # Orchestrator with symmetric teardown
│       │   └── BootReceiver.kt
│       └── ui/
│           ├── MainActivity.kt             # Permissions + Compose entry point
│           ├── MainViewModel.kt            # StateFlow adapter
│           └── components/
│               └── UiComponents.kt         # StatusCard, CredentialsDisplay, QR code
└── todo.md                                 # Iterative development master plan
```

---

## Dependencies

| Library | Purpose |
|---|---|
| MinimalFTP (`com.guichaguri:minimalftp:1.0.5`) | Pure-Java RFC 959 FTP server |
| ZXing Core (`com.google.zxing:core:3.5.2`) | QR code generation |
| Hilt (`com.google.dagger:hilt-android:2.48`) | Dependency injection |
| Jetpack Compose BOM `2023.10.01` | Reactive UI |
| Coroutines `1.7.3` | Async/structured concurrency |
| DocumentFile `1.0.1` | SAF helper wrappers |

---

## Licence

MIT — see `LICENSE` for details.
