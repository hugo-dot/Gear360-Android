# Gear 360 SM-R210 Physical Bluetooth Notes

Target camera: Samsung Gear 360 2017 SM-R210.

Current Android 16 / S8 observations:

- Android sees the camera as `Gear 360 (7F29)`.
- The camera appears as a dual-mode Bluetooth device.
- Repeated `BluetoothDevice.createBond()` with Android transport auto falls back to `BOND_STATE_NONE`.
- Historical dumps show BR/EDR classic connections can succeed before the bond is later lost.
- A candidate RFCOMM service was observed:
  - UUID: `a49eb41e-cb06-495c-9f4f-bb80a90cdf00`
  - RFCOMM SCN: `1`
  - MTU: `990`

The app must not treat `BOND_BONDED`, Wi-Fi connectivity, or an open RFCOMM socket as a ready Gear 360 control session.

Native transport milestone:

1. Run SDP against the selected Gear 360.
2. Prefer the observed UUID only when SDP confirms it, with a Gear 360 fallback for diagnostics.
3. Open RFCOMM.
4. Dump all RX bytes in HEX.
5. Do not send Gear 360 JSON until Samsung Accessory framing and logical channel 204 are identified.
