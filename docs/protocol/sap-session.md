# Samsung Accessory Compatible Session Notes

Known high-level service profile from `accessoryservices.xml`:

- profile name: `G_360_APP`
- profile id: `/system/DI_360_2D`
- transport: `TRANSPORT_BT`
- logical channels: `204`, `222`, `230`

Important distinction:

- RFCOMM SCN/channel `1` is the physical Bluetooth socket channel observed in Android dumps.
- SASocket channel `204` is a Samsung Accessory logical channel used by the existing Gear 360 JSON protocol.

Current implementation state:

- `LegacySamsungAccessoryTransport` remains available as a diagnostic backend.
- `NativeGear360Transport` now opens the physical RFCOMM candidate and logs raw frames.
- `SapFrameDecoder` intentionally refuses to decode frames until HCI fixtures identify the frame layout.
- `SapFrameEncoder` intentionally throws until the frame layout is known.

Fields still to identify from HCI capture:

- frame boundary markers
- length field
- command/session type
- service profile negotiation
- logical channel negotiation
- channel id encoding for `204`, `222`, and `230`
- keepalive format
- disconnect framing
