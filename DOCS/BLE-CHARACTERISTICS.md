# Garmin Alpha BLE Characteristics

Detailed documentation of the Bluetooth Low Energy interface on Garmin Alpha dog tracking handhelds.

## Service UUID

```
Garmin Multi-Link Service: 6a4e2800-667b-11e3-949a-0800200c9a66
```

This is a custom Garmin service, not a standard Bluetooth SIG profile.

## Characteristic Map

The service exposes paired characteristics for bidirectional communication:

| Notify (Read) | Write (Command) | Purpose |
|---------------|-----------------|---------|
| `6a4e2810` | `6a4e2820` | Unknown / Control |
| `6a4e2811` | `6a4e2821` | **Primary position data** |
| `6a4e2812` | `6a4e2822` | Unknown |
| `6a4e2813` | `6a4e2823` | Position data (alternate) |
| `6a4e2814` | `6a4e2824` | Unknown |

### Key Discovery: Position Data Channel

**Position data flows on `6a4e2811`**, not `6a4e2813` as initially assumed from Gadgetbridge documentation.

This was discovered through nRF Connect testing on December 4, 2024. The `2811` characteristic receives large packets (~180 bytes) containing protobuf-encoded position data when Garmin Explore triggers the data stream.

## Enabling Notifications

Each notify characteristic requires CCCD (Client Characteristic Configuration Descriptor) write to enable:

```kotlin
val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

// For each notify characteristic:
gatt.setCharacteristicNotification(characteristic, true)
val descriptor = characteristic.getDescriptor(CCCD_UUID)
descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
gatt.writeDescriptor(descriptor)
```

**Important**: BLE only allows one descriptor write at a time. Chain writes via `onDescriptorWrite` callback.

## Data Flow Behavior

### Without Garmin Explore

When GdogTAK connects alone:
- CCCD writes succeed on all 5 characteristics
- Only periodic 2-byte heartbeat/ack packets received
- **No position data** regardless of collar movement

### With Garmin Explore Running

When Garmin Explore is also active:
- Position data immediately starts flowing on `6a4e2811`
- Updates every 2-5 seconds during movement
- Large packets (~180 bytes) with embedded protobuf

### Implication

Garmin Explore sends an **initialization command** to one of the `282x` write characteristics that triggers the position data stream. This command has not yet been captured.

## Packet Structure

### Position Data Packet (6a4e2811)

```
Offset  Content
------  -------
0-3     Header/sequence (varies)
4       Packet type (0x02 = position update)
5-6     Length prefix
7+      Protobuf payload
```

Example packet (hex):
```
DB-AD-00-02-49-05-2B-99-9E-01-01-01-01-02-35-01-01-02-35-01-01-23-6A-33-3A-31-0A-2F-0A-0C-08-80-BC-D7-F1-03-10-FF-EF-F7-A7-0A-18-F5-DC-D1-9C-04...
```

### Device Type Markers

Within the protobuf payload:
- `0x35` (53) = Dog collar (TT25, TT20, etc.)
- `0x28` (40) = Handheld (Alpha 300i, 200i, etc.)

### Coordinate Block Signature

Position data is preceded by: `0A 0C 08`

This indicates the start of the protobuf coordinate structure:
- `0A` = Field 1, wire type 2 (length-delimited)
- `0C` = Length 12 bytes
- `08` = Field 1, wire type 0 (varint) - latitude

## Coordinate Encoding

Coordinates are encoded as **Garmin semicircles** in protobuf varints:

```
Latitude semicircles:  0x80 0xBC 0xD7 0xF1 0x03 → decode → 1040415616
Longitude semicircles: 0xFF 0xEF 0xF7 0xA7 0x0A → decode → 2765455359 (signed: -1529511937)

Conversion: degrees = semicircles × (180 / 2³¹)

Latitude:  1040415616 × (180 / 2147483648) = 43.7211°
Longitude: -1529511937 × (180 / 2147483648) = -116.0158°
```

## BLE Connection Behavior

### Device Name

The Alpha advertises as "Alpha" (seen via BLE scan), though the full model name is "Alpha 300i".

### MAC Address Privacy

The Alpha uses **BLE address randomization**. The advertised MAC (e.g., `FA:1A:C1:B3:DC:2F`) is not a Garmin OUI (`0C:BF:32`). This means:
- MAC address may change between sessions
- Filter by device name, not MAC address

### Connection Exclusivity

**Only one BLE client can connect at a time.** Once Garmin Explore connects, the Alpha stops advertising and other apps cannot connect.

To allow GdogTAK to connect:
1. Force-stop Garmin Explore
2. Revoke Explore's "Nearby devices" permission
3. GdogTAK can now discover and connect

## HTTP API Proxy

Interestingly, the Alpha proxies HTTP requests through BLE to the phone's internet connection. Observed in packet captures:

```
https://api.dog.garmin.com/hunts/current
Authorization: Bearer [token]
Accept: application/x-protobuf
```

This is how the Alpha syncs with Garmin's cloud services when the phone has connectivity.

## Testing Tools

### nRF Connect (Nordic Semiconductor)

Essential for protocol discovery:
1. Scan and connect to "Alpha"
2. Navigate to Garmin service `6a4e2800...`
3. Enable notifications on all `281x` characteristics
4. Log packets to file for analysis

### Android Logcat

Filter for GdogTAK output:
```bash
adb logcat -s BleTrackingService:D GarminProtocol:D
```

### Wireshark + btsnoop

For capturing what Garmin Explore sends:
1. Enable Bluetooth HCI snoop log (requires root or developer options)
2. Connect Explore to Alpha
3. Pull btsnoop_hci.log
4. Analyze in Wireshark with Bluetooth filter

## Open Questions

1. **What is the init command?** What does Explore write to trigger position streaming?
2. **Which characteristic receives it?** Likely `6a4e2823` based on pairing with `6a4e2813`
3. **Is it a one-time command or periodic?** Does it need to be resent?
4. **Authentication?** Is there a handshake or pairing requirement?
5. **Other device markers?** What values represent Alpha 200i, Alpha 100, TT20, etc.?

## References

- [Gadgetbridge Garmin Protocol](https://codeberg.org/Freeyourgadget/Gadgetbridge) — Initial protocol insights
- [Bluetooth Core Specification](https://www.bluetooth.com/specifications/specs/) — GATT/ATT details
- [Protocol Buffers](https://developers.google.com/protocol-buffers) — Varint encoding reference
