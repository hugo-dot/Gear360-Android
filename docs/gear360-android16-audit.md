# Gear360App Android 16 Audit

Date: 2026-08-31
Camera: Samsung Gear 360 2017 SM-R210
Primary target phone: Samsung Galaxy S8, /e/OS, Android 16

## Build Status

The current project compiles successfully from the outer Gradle root:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Crash Cause

The current runtime crash on generic Android/e/OS is caused by the legacy Samsung
Accessory dependency not being isolated correctly.

The old code path is:

```text
Gear360Service.initSAM()
  -> SamAccessoryManager.getInstance(applicationContext, samListener)
```

`SamAccessoryManager.getInstance(...)` can throw
`com.samsung.android.sdk.SsdkUnsupportedException`. This exception extends
`java.lang.Exception`, not `RuntimeException`. The app only caught
`RuntimeException`, so on a phone without a compatible Samsung Accessory Service
Framework this can crash the service thread instead of producing a clean
Disconnected/Error state.

The same risk existed around:

```text
SamAccessoryManager.connect(...)
SamAccessoryManager.disconnect(...)
SAAgentV2.requestAgent(...)
SAAgentV2.findPeerAgents(...)
SASocket.send(...)
```

These calls are now guarded so Samsung Accessory failure becomes a logged,
non-fatal disconnected state.

ADB logcat confirmation is still pending because the S8 was not visible to ADB
when tested. The install helper only saw `SM-A055F`, not `SM-G950F`.

## Current Architecture

```text
Android Activity/UI
  -> Gear360Service
     -> SamAccessoryManager
     -> BTMProviderService extends SAAgentV2
        -> SASocket
           -> channel 204 JSON messages
Gear 360
```

There is no native Android Bluetooth socket/GATT driver yet.

## Samsung Dependencies

Required local jars:

```text
app/sdk/accessory-v2.6.4.jar
app/sdk/Addon.jar
app/sdk/sdk-v1.0.0.jar
```

Observed classes:

```text
com.samsung.android.sdk.accessory.SAAgentV2
com.samsung.android.sdk.accessory.SASocket
com.samsung.android.sdk.accessory.SAService
com.samsung.android.sdk.accessory.filetransfer.SAFileTransfer
com.samsung.android.sdk.accessorymanager.SamAccessoryManager
com.samsung.android.sdk.accessorymanager.SamDevice
```

Manifest declarations:

```text
com.samsung.accessory.permission.ACCESSORY_FRAMEWORK
com.samsung.accessory.permission.ACCESSORY_FRAMEWORK_ADMIN
com.samsung.android.sdk.accessory.SAService
com.samsung.android.sdk.accessory.SAJobService
com.samsung.android.sdk.accessory.RegisterUponInstallReceiver
AccessoryServicesLocation=/res/xml/accessoryservices.xml
```

Accessory profile:

```text
serviceProfile name=G_360_APP
role=provider
id=/system/DI_360_2D
transport=TRANSPORT_BT
channels=204,222,230
```

## Current Protocol

The reusable part is the JSON message protocol already modeled in Kotlin.

Known message ids:

```text
info
config-info
widget-info-req
widget-info-rsp
widget-info-update
date-time-req
date-time-rsp
cmd-req
cmd-rsp
shot-req
shot-rst
device-desc-url
bigdata-req
notify
```

Known safe commands already implemented:

```text
date-time-rsp
widget-info-req
widget-info-rsp
phone device info
change mode
change timer
change beep volume
change LED
change auto power-off
change looping video time
photo capture
video record
record stop
liveview request
```

Important: these commands are known at the JSON layer, but the real underlying
Bluetooth transport is still hidden by Samsung Accessory Protocol.

## Current Wi-Fi / Media

Existing code knows the preview endpoint:

```text
http://192.168.107.1:7679/livestream_high.avi
```

`WifiUtils` already uses modern `WifiNetworkSpecifier` and
`ConnectivityManager.requestNetwork(...)` for Android Q+, then calls
`bindProcessToNetwork(network)`.

Media listing/download is not fully implemented in the original project. A
prototype importer now tries known local HTTP addresses, but true gallery support
needs a dedicated `Gear360WifiDriver` and validated endpoints.

## Android 16 Compatibility Notes

The manifest already declares:

```text
BLUETOOTH_SCAN
BLUETOOTH_CONNECT
BLUETOOTH_ADVERTISE
NEARBY_WIFI_DEVICES
INTERNET
ACCESS_NETWORK_STATE
ACCESS_WIFI_STATE
CHANGE_NETWORK_STATE
CHANGE_WIFI_STATE
```

Startup permissions request:

```text
Android 12+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT
Android 13+: NEARBY_WIFI_DEVICES optional
Legacy: ACCESS_FINE_LOCATION
```

Remaining work:

- convert optional Wi-Fi permission failures into explicit UI errors
- target Android 16 after replacing Samsung SDK crash paths
- ensure all local network HTTP requests are routed through the camera Wi-Fi
  `Network`, not cellular
- avoid relying on deprecated `WifiConfiguration` except on old Android releases

## Reusable Parts

- `BTMessage.kt`: JSON protocol model and parser
- `MessageSender.kt`: safe command serialization
- `MessageHandler.kt`: message dispatch
- `Gear360Info`, `Gear360Status`, `Gear360Config`
- `CameraMode`, `CaptureState`, `DeviceType`
- live stream parsing prototypes
- modern runtime permission helper
- modern Wi-Fi request helper, after cleanup

## Parts To Replace

- `SamAccessoryManager`
- `SAAgentV2`
- `SASocket`
- Samsung manifest services/receiver
- SAP-only connection state in `Gear360Service`
- UI calls that assume Samsung Accessory connection means camera readiness

## Next Implementation Plan

Phase 2:

- add a stable `Gear360Controller` facade
- add explicit `Gear360ConnectionState`
- route all UI through controller/repository
- keep the old Samsung transport as a temporary adapter
- keep crash isolation and detailed TX/RX logs

Phase 3:

- create `Gear360BluetoothDriver`
- first implement non-destructive discovery/introspection only
- record Android scan data: name, address, bond state, device type, UUIDs,
  advertised BLE records
- do not send unknown commands

Phase 4:

- implement direct command transport only after the real transport is proven:
  Classic RFCOMM/SPP, BLE GATT, or another channel
- reuse the existing JSON protocol once the byte transport is known

Phase 5:

- create `Gear360WifiDriver`
- connect to camera AP using `WifiNetworkSpecifier`
- keep a `Network` reference
- open HTTP/socket connections through that network explicitly

Phase 6:

- validate media listing endpoints
- download through Storage Access Framework or MediaStore
- add tests for protocol parsing, state transitions, timeouts, and permission
  failures
