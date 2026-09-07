# Gear 360 SM-R210 Bluetooth/SAP diagnostics

This iteration validates only the control path:

Bluetooth bond -> Samsung Accessory transport -> SAP peer -> SASocket -> channel 204 JSON -> config-info -> READY.

Wi-Fi, gallery and live view are intentionally disabled in the simple camera UI during this diagnostic pass.

## Expected logcat sequence

Use `diagnose-s8.bat SERIAL` after connecting the S8 with USB debugging enabled.

The important tags are:

- `G360-BT`
- `G360-CONNECTION`
- `G360-SAP`
- `G360-RX`
- `G360-TX`
- `G360-PROTOCOL`
- `G360-CAPTURE`
- `AndroidRuntime`

Healthy sequence:

```text
G360-BT: BOND_BONDED
G360-CONNECTION: state=ACCESSORY_CONNECTING
G360-CONNECTION: state=ACCESSORY_CONNECTED
G360-CONNECTION: state=SAP_DISCOVERING
G360-SAP: onFindPeerAgentsResponse result=PEER_AGENT_FOUND
G360-SAP: Requesting SAP service connection
G360-SAP: onServiceConnectionResponse result=CONNECTION_SUCCESS
G360-SAP: SASocket CONNECTED
G360-CONNECTION: state=PROTOCOL_SYNCING
G360-RX: channel=204 len=...
G360-RX: UTF8={... "msgId":"date-time-req" ...}
G360-TX: channel=204 len=...
G360-RX: channel=204 len=...
G360-RX: UTF8={... "msgId":"config-info" ...}
G360-CONNECTION: READY config-info received
G360-CONNECTION: state=READY
```

If `FINDPEER_SERVICE_NOT_FOUND` or `FINDPEER_DEVICE_NOT_CONNECTED` appears, the failure is before channel 204 and must be investigated in Samsung Accessory Service/profile registration, not in the capture buttons.

If `SASocket CONNECTED` appears but no `G360-RX channel=204` arrives within 10 seconds, the app reports:

```text
SAP socket established but Gear360 protocol silent
```

If channel 204 is active but `config-info` never arrives, the app reports:

```text
Gear360 protocol active but initial configuration not received
```

## HCI snoop fallback

If Samsung Accessory cannot establish a peer/socket on /e/OS, capture a Bluetooth HCI snoop log on a setup where the original Gear 360 connection works, then compare it with this app:

1. Enable Developer options.
2. Enable Bluetooth HCI snoop logging.
3. Reboot if Android asks for it.
4. Connect the official working app to the SM-R210 and wait until camera control is ready.
5. Pull the generated `btsnoop_hci.log` with adb or from the bug report archive.
6. Repeat with Gear360App.
7. Compare SDP/RFCOMM/L2CAP handshakes and Samsung Accessory framing.

Do not map SAP logical channel `204` to an RFCOMM channel without packet evidence.
