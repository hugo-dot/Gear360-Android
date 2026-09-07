# Gear 360 Logical Channel 204

The existing project already contains the useful Gear 360 JSON protocol above Samsung Accessory:

- `MessageSender.sendCaptureRequest(CameraMode.PHOTO)` sends `CaptureCommand.CAPTURE`.
- video capture sends `CaptureCommand.RECORD`.
- video stop sends `CaptureCommand.RECORD_STOP`.
- all of these are sent through logical channel `204`.

Expected initial JSON-level sequence after channel 204 is truly open:

1. camera -> `date-time-req`
2. phone -> date-time response
3. camera -> `widget-info-req`
4. phone -> widget-info response
5. phone -> widget-info request
6. camera -> widget/status response
7. phone -> phone info
8. camera -> device info
9. camera -> `config-info`

`READY` must only be declared after valid Gear 360 JSON is received, ideally after `config-info`.

Never send `shot-req`, `record`, or `record stop` before the native transport has:

- physical RFCOMM connected
- SAP-compatible session negotiated
- logical channel `204` open
- Gear 360 protocol synchronized
