# Samsung Accessory SDK libraries

This directory contains the legacy Samsung Accessory client libraries required by the
`LegacySamsungAccessoryTransport` build:

- `Addon.jar`
- `accessory-v2.6.4.jar`
- `sdk-v1.0.0.jar`

They expose the Samsung Accessory API to this APK. They do not embed or replace the
external Android package `com.samsung.accessory`. The native transport remains a separate,
experimental clean-room implementation.
