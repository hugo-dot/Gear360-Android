# Android 9 Reference Capture Plan

Reference app: Samsung Gear 360 Manager `com.samsung.android.gear360manager`, version `1.5.00.1`.

Goal: obtain a clean-room behavioral trace of a working Android 9 connection.

Capture procedure:

1. Enable Bluetooth HCI snoop logging.
2. Start with the Gear 360 powered off.
3. Start the capture.
4. Put the Gear 360 in `Connect to Android` / phone mode.
5. Open the official Samsung Gear 360 app.
6. Wait until the camera is fully connected and ready.
7. Take exactly one photo.
8. Start one short video.
9. Stop the video after a few seconds.
10. Disconnect cleanly.

Analysis targets:

- SDP discovery
- selected UUID and RFCOMM SCN
- first bytes after RFCOMM connection
- Samsung Accessory session negotiation
- `/system/DI_360_2D` profile selection
- logical channel negotiation for `204`, `222`, `230`
- first channel 204 JSON payload
- capture/photo/video command framing

The resulting binary snippets should be anonymized and stored as unit-test fixtures before implementing `SapFrameDecoder` and `SapFrameEncoder`.
