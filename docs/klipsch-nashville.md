# Klipsch Nashville BLE notes

Target observed: `CB:4E:FD:20:91:D0`

## Confirmed GATT identity

Three read passes, including a power cycle, produced stable values:

| ATT handle | Value | Interpretation |
|---|---|---|
| `0x0003` | `Klipsch Nashville` | Device Name |
| `0x0005` | `0600060000000a00` | Preferred connection parameters |
| `0x0008` | `Klipsch Group, Inc.\0` | Manufacturer |
| `0x000A` | `1072056\0` | Product/model-like identifier |
| `0x000C` | `107197524260115` | Serial-like identifier |
| `0x000E` | `1` | Device Information value |
| `0x0010` | `1.0.1\0` | revision/version value |
| `0x0012` | `1.0.1\0` | revision/version value |
| `0x0015` | `4b25` | likely battery: `0x4b = 75`, followed by `%` |
| `0x001F` | `1.0.1\0` | revision/version value |

The captured values above were byte-identical across P1/P2/P3, so they are persistent across the tested power cycle. This does **not** mean the BLE reads are a flash/firmware image.

## Useful notification observations

ATT notifications (`0x1B`) repeatedly included:

- handle `0x0015`, value `4b` → strongly consistent with battery level 75%
- handle `0x001C`, value `00`
- handle `0x0025`, value `00`

Many `0100` reads are consistent with CCCD state (`notifications enabled`) rather than application configuration.

## Vendor services discovered

Klipsch exposes a substantial vendor GATT surface under UUIDs derived from:

- `da6d0f01-0d18-442c-babe-f85b5baa6f11`
- `da6d0fa1-0d18-442c-babe-f85b5baa6f11`
- `da6d0fb1-0d18-442c-babe-f85b5baa6f11`
- `da6d0fc1-0d18-442c-babe-f85b5baa6f11`
- `da6d0fd1-0d18-442c-babe-f85b5baa6f11`
- `da6d0fe1-0d18-442c-babe-f85b5baa6f11`

A second service is particularly interesting:

- service `e49a25f8-f69a-11e8-8eb2-f2801f1b9fd1`
- characteristic `e49a28e1-f69a-11e8-8eb2-f2801f1b9fd1` `[E N R]`
- characteristic `e49a25e0-f69a-11e8-8eb2-f2801f1b9fd1` `[R W WNR]`

Treat this as a candidate transport/management/OTA surface until traffic proves what it is. Do not infer a DFU protocol merely from the properties.

## Pairing behavior

Observed SMP sequence repeatedly reached Pairing Confirm / Pairing Random and then failed. Failures included SMP reasons `0x04`, `0x09`, and an outgoing `0x0c`. nRF Connect also reported Android bonding failure `AUTH FAILED (1)`.

Normal GATT discovery/read access was nevertheless possible without a successful bond.

## Firmware acquisition strategy

A normal BLE GATT connection does not expose a block device, so `dd`/`cat` cannot directly clone the speaker flash. The useful next path is passive protocol recovery:

1. capture Bluetooth HCI traffic while the official Klipsch application connects;
2. identify reads/writes to the vendor UUIDs;
3. correlate commands with app actions and any firmware/update check;
4. recover an OTA/download URL or firmware payload if the legitimate update flow exposes one;
5. only then feed recovered firmware blobs to tools such as `binwalk`, `strings`, and `bulk_extractor`.

Current rule: capture first, write later. Unknown vendor characteristics should not receive guessed payloads.
