# AndroidNetworkHub — todo.md
# Iterative Development Master Plan

## Phase 1 — Environment Configuration and Permission Declarations ✅

- [x] Initialise project scaffold (`minSdk=26`, `targetSdk=28`)
- [x] Define `AndroidManifest.xml` with all required permissions:
  - [x] `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
  - [x] `ACCESS_FINE_LOCATION` (mandatory for LocalOnlyHotspot on API 28)
  - [x] `CHANGE_WIFI_MULTICAST_STATE` (for mDNS MulticastLock)
  - [x] `FOREGROUND_SERVICE`, `WAKE_LOCK`
  - [x] `RECEIVE_BOOT_COMPLETED` (for BootReceiver)
- [x] Register `HubForegroundService` and `BootReceiver` in manifest
- [x] Declare `foregroundServiceType="dataSync|connectedDevice"` for API 34 forward-compat
- [x] Configure `network_security_config.xml` to permit cleartext FTP on 192.168.43.0/24

---

## Phase 2 — Core Hardware Abstractions and Storage Resolution ✅

- [x] Implement `SafStorageBridge.kt`:
  - [x] Build `ACTION_OPEN_DOCUMENT_TREE` intent
  - [x] Persist URI via `ContentResolver.takePersistableUriPermission`
  - [x] Validate persisted grants on each access via `persistedUriPermissions`
  - [x] Provide `openInputStream` / `openOutputStream` wrappers
- [x] Implement `FtpVirtualFileSystem.kt`:
  - [x] Implement `IFileSystem<DocumentFile>`
  - [x] Map FTP path strings to `DocumentFile.fromTreeUri` traversals
  - [x] Map `STOR` / `RETR` to SAF `ContentResolver` streams
  - [x] Support `MKD`, `DELE`, `RMD`, `RNFR/RNTO`, `COPY`, `MOVE`
  - [x] Implement 64KB buffered `copyTo` for efficient bulk transfer
- [x] Implement `ManageWakeLockUseCase.kt`:
  - [x] Acquire `PARTIAL_WAKE_LOCK` with 6-hour safety timeout
  - [x] Symmetric `acquire()` / `release()` with idempotency guards
  - [x] `setReferenceCounted(false)` to prevent double-release errors

---

## Phase 3 — Network Provisioning and Hotspot Instantiation ✅

- [x] Implement `HotspotManagerImpl.kt`:
  - [x] Wrap `LocalOnlyHotspotCallback` in Kotlin `callbackFlow`
  - [x] Extract `SSID` and `preSharedKey` from `reservation.wifiConfiguration`
  - [x] Emit `HotspotInfo` on `onStarted`
  - [x] Map `onFailed` error codes to descriptive `HotspotStartException`
  - [x] Implement `stopHotspot()` calling `reservation.close()`
- [x] Implement `StartHotspotUseCase.kt` as domain-layer adapter

---

## Phase 4 — Server Binding and Multicast Discovery ✅

- [x] Implement `StartFtpServerUseCase.kt`:
  - [x] Bind MinimalFTP server to port 2121 on `192.168.43.1`
  - [x] Register `FtpVirtualFileSystem` as the custom `IFileSystem`
  - [x] Configure `NoOpAuthenticator` for anonymous LAN access
  - [x] Run `listenSync()` on `Dispatchers.IO` (non-blocking for the main scope)
  - [x] Implement graceful `shutdown()` with `isRunning` guard
- [x] Implement `NsdBroadcaster.kt`:
  - [x] Register `_ftp._tcp.` service type with `NsdManager`
  - [x] Acquire `MulticastLock` with `setReferenceCounted(true)`
  - [x] Implement symmetric `register()` / `unregister()` teardown

---

## Phase 5 — Service Orchestration and UI Integration ✅

- [x] Implement `HubForegroundService.kt`:
  - [x] Call `startForeground()` in `onCreate()` with ongoing notification
  - [x] Implement 5-step startup sequence with `SupervisorJob` error isolation
  - [x] Implement symmetric teardown in `onDestroy()`
  - [x] Expose singleton `statusFlow: StateFlow<ServerStatus>`
  - [x] Add "Stop" action to notification via `PendingIntent`
- [x] Implement `BootReceiver.kt` for post-reboot auto-start
- [x] Implement `MainViewModel.kt`:
  - [x] Collect `HubForegroundService.statusFlow` via `stateIn`
  - [x] Expose `startServer()` / `stopServer()` dispatch methods
  - [x] Expose `onSdCardUriResult()` for SAF grant persistence
- [x] Build Compose UI (`MainActivity.kt`):
  - [x] Request runtime permissions on launch
  - [x] Launch SAF document tree picker for SD card access
  - [x] Reactive `StatusCard` component with colour-coded states
  - [x] `CredentialsDisplay` component showing SSID, password, FTP URL
  - [x] ZXing QR code generation (WIFI: URI scheme)
  - [x] Start/Stop control buttons with service intent dispatch
  - [x] SD card access grant button with persisted state display

---

## Phase 6 — Testing and Quality Assurance [ PENDING ]

- [ ] Unit tests for `SafStorageBridge` URI persistence logic
- [ ] Unit tests for `ManageWakeLockUseCase` acquire/release symmetry
- [ ] Unit tests for `FtpVirtualFileSystem` path resolution
- [ ] Integration test: FTP STOR + RETR round-trip via loopback
- [ ] Manual test: connect FileZilla client to upload and download a 1GB file
- [ ] Manual test: mDNS discovery on Windows (via File Explorer → Network)
- [ ] Manual test: QR code Wi-Fi join from an iOS device

---

## Phase 7 — Hardening and Optimisation [ PENDING ]

- [ ] Add `UserAuthenticator` option with UI for username/password configuration
- [ ] Add passive mode IP binding validation (confirm `192.168.43.1` assignment)
- [ ] Add real-time connected client counter from MinimalFTP session callbacks
- [ ] Add transfer speed meter (bytes/sec) in the running state card
- [ ] Add `BootReceiver` toggle in a Settings screen
- [ ] Investigate 5 GHz band availability on Exynos S8+ variants
- [ ] Add optional TLS/SSL mode using `FTPServer.setSSLContext()`
- [ ] Evaluate adding WebDAV (NanoHTTPD + nanodav) as an optional secondary protocol

---

## Known Architecture Constraints (from PDF specification)

| Constraint | Impact | Mitigation |
|---|---|---|
| Android 9 LocalOnlyHotspot cannot set custom SSID/password | Credentials are system-generated each session | Display + QR code in UI |
| Exynos S8+ may lock hotspot to 2.4 GHz | Lower throughput than 5 GHz | Protocol buffers sized for 2.4 GHz latency |
| FAT32 SD cards limit individual file size to 4 GB | Large media files fail | Require exFAT; document in README |
| SAF imposes overhead vs direct POSIX I/O | Slightly lower throughput | 64KB transfer buffer; async IO dispatcher |
| PARTIAL_WAKE_LOCK must be released on shutdown | Battery drain if leak occurs | 6-hour timeout + symmetric teardown |
| MulticastLock must be held for mDNS to work when screen is off | NSD invisible without lock | Acquired before hotspot start, released last |
