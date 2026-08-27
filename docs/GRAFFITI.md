# BT Graffiti

Graffiti is the BT toolkit module for controlled identity/config changes on Bluetooth devices the operator owns or is authorized to test.

## Goals

- `rename`: persistent Bluetooth device-name change where supported.
- `tag`: write a recognizable operator tag into a known vendor/config field.
- `identify`: temporary or reversible identity marker for lab work.
- `mark`: persistent lab marker when a device profile documents a safe field.

## Core rule

Never spray arbitrary writes at unknown GATT characteristics. Discovery is read-only first. A Graffiti action becomes write-capable only after BT has a known writable target or a learned vendor command from captures.

## Persistent rename workflow

1. Scan and fingerprint the device.
2. Check Generic Access Device Name (`0x2A00`).
3. If writable, build UTF-8 payload and perform an explicit armed write.
4. Read back the characteristic when possible.
5. Disconnect and reconnect.
6. Power-cycle device.
7. Rescan advertisement and record whether the name persisted.

If `0x2A00` is read-only but a vendor transport such as Nordic UART Service exists, capture the vendor application changing the name and learn its framing instead of guessing.

## Framing model

Vendor commands are represented as:

`prefix + mutable body + suffix`

Graffiti should normally mutate only the body. Prefix/suffix may come from Java/device profiles or be inferred from repeated captures. Full-frame mutation must remain a separate explicit mode.

## Evidence log

Every Graffiti test should emit JSONL containing:

- device fingerprint
- service UUID
- characteristic UUID
- mode (`rename`, `tag`, `identify`, `mark`)
- prefix/body/suffix
- complete payload
- timestamp
- write result
- readback
- reconnect result
- post-power-cycle result
- advertisement name before/after

## UI

The browser/APK should show a dedicated **Graffiti** panel with:

- action selector
- desired tag/name
- detected target
- confidence / evidence
- dry-run payload preview
- `ARM WRITE` switch
- execute button
- verify-persistence button

Dry-run is the default.