# Garmin Alpha BLE Protocol

Reverse-engineered documentation of the Bluetooth Low Energy protocol used by Garmin Alpha dog tracking handhelds.

**Status**: Working decoder for position data. Init command for standalone operation not yet discovered.

**Last Updated**: December 2024

**Tested Hardware**: Alpha 300i + TT25 collar

---

## Quick Summary

For those who just want to understand it:

1. **Garmin Alpha** handhelds (300i, 200i, etc.) communicate with phones via Bluetooth Low Energy
2. They use a custom **Garmin Multi-Link** service (not standard Bluetooth profiles)
3. Position data is **protobuf-encoded** with coordinates in Garmin semicircles
4. A **0x35 marker** identifies dog collars; **0x28** identifies handhelds
5. **Garmin Explore app must be running** to trigger data streaming (for now)

---

## Service Architecture

### Garmin Multi-Link Service

```
Service UUID: 6a4e2800-667b-11e3-949a-0800200c9a66
```

### Characteristics

| UUID | Type | Purpose |
|------|------|---------|
| `6a4e2810` | Notify | Unknown / heartbeat |
| `6a4e2811` | Notify | **Primary position data** |
| `6a4e2812` | Notify | Unknown |
| `6a4e2813` | Notify | Position data (alternate) |
| `6a4e2814` | Notify | Unknown |
| `6a4e2820` | Write | Command (pairs with 2810) |
| `6a4e2821` | Write | Command (pairs with 2811) |
| `6a4e2822` | Write | Command (pairs with 2812) |
| `6a4e2823` | Write | Command (pairs with 2813) |
| `6a4e2824` | Write | Command (pairs with 2814) |

### Notification Subscription

Standard BLE CCCD (Client Characteristic Configuration Descriptor) write:

```
Descriptor UUID: 00002902-0000-1000-8000-00805f9b34fb
Value: 0x01 0x00 (enable notifications)
```

---

## Position Data Format

### Packet Example

Raw hex from characteristic `6a4e2811`:

```
DB-AD-00-02-49-05-2B-99-9E-01-01-01-01-02-35-01-01-02-35-01-01-23-6A-33-3A-31-0A-2F-0A-0C-08-80-BC-D7-F1-03-10-FF-EF-F7-A7-0A-18-F5-DC-D1-9C-04...
```

### Structure

```
Offset  Length  Content
------  ------  -------
0-3     4       Header/sequence number (varies per packet)
4       1       Packet type: 0x02 = position update
5-6     2       Payload length (little-endian)
7+      var     Protobuf payload containing device info + coordinates
```

### Device Type Markers

Within the payload, device type is encoded:
- **0x35** (decimal 53) = Dog collar (TT25, TT20, TT15, T5)
- **0x28** (decimal 40) = Handheld (Alpha 300i, 200i, 200, 100)

Pattern to find: `01-02-XX-01-01-02-XX-01` where XX is the device type.

### Coordinate Block

Coordinates are preceded by signature: `0A 0C 08`

```
0A      = Protobuf field 1, wire type 2 (length-delimited)
0C      = Length 12 bytes
08      = Field 1 (latitude), wire type 0 (varint)
[varint]= Latitude in semicircles
10      = Field 2 (longitude), wire type 0 (varint)
[varint]= Longitude in semicircles
18      = Field 3 (altitude?), wire type 0 (varint)
[varint]= Altitude value
```

---

## Coordinate Encoding

### Semicircles

Garmin uses "semicircles" - a 32-bit signed integer representation:
- Full circle = 2³¹ semicircles = 2,147,483,648
- 180° = 2³⁰ semicircles
- Precision: ~0.009mm at equator

### Conversion

```
degrees = semicircles × (180 / 2³¹)
degrees = semicircles × 8.381903171539307e-8
```

### Protobuf Varint Decoding

Coordinates are stored as protobuf varints:
- 7 bits of data per byte
- MSB = 1 means more bytes follow
- LSB of result comes first

Example latitude `80 BC D7 F1 03`:
```
Decode varint: 1040415616
Convert: 1040415616 × (180/2147483648) = 43.7211°
```

Example longitude `FF EF F7 A7 0A` (negative):
```
Decode varint: 2765455359
Signed 32-bit: 2765455359 - 2³² = -1529511937
Convert: -1529511937 × (180/2147483648) = -116.0158°
```

---

## Data Flow

### Normal Flow (with Garmin Explore)

```
1. Phone connects to Alpha via BLE
2. Phone subscribes to notify characteristics (CCCD write)
3. Garmin Explore sends init command to 282x characteristic
4. Alpha starts streaming position data on 2811
5. Updates arrive every 2-5 seconds during movement
```

### Without Init Command

```
1. Phone connects to Alpha via BLE
2. Phone subscribes to notify characteristics (CCCD write)
3. Only heartbeat/ack packets received (2 bytes each)
4. NO position data regardless of collar movement
```

### The Init Command Mystery

We know:
- Garmin Explore sends something to trigger data flow
- It's probably written to `6a4e2823` (pairs with position char `6a4e2813`)
- Data flows on `6a4e2811` after Explore connects
- Could be a simple enable byte or complex handshake

To capture it:
1. Root a phone
2. Enable Bluetooth HCI snoop log
3. Connect Garmin Explore to Alpha
4. Pull btsnoop_hci.log
5. Analyze in Wireshark

---

## BLE Connection Details

### Device Discovery

- **Advertised name**: "Alpha" (not "Alpha 300i")
- **MAC address**: Randomized (not Garmin OUI `0C:BF:32`)
- **Filter by**: Device name containing "Alpha"

### Connection Exclusivity

Only one BLE client can connect at a time. Once Garmin Explore connects:
- Alpha stops advertising
- Other apps cannot discover it
- Must force-stop Explore to allow other connections

### MTU

Default BLE MTU may need adjustment for large packets. Request MTU 512:
```kotlin
gatt.requestMtu(512)
```

---

## Additional Features

### HTTP Proxy

The Alpha can proxy HTTP requests through BLE to use the phone's internet:

```
GET https://api.dog.garmin.com/hunts/current
Authorization: Bearer [token]
Accept: application/x-protobuf
```

This is how Garmin Explore syncs hunt data with Garmin's cloud.

### Battery Status

Periodic 2-byte packets on `6a4e2810` may contain battery/status info. Format TBD.

---

## Testing Methodology

### Tools Used

1. **nRF Connect** (Nordic Semiconductor) - BLE packet capture
2. **Android Logcat** - Debug output from GdogTAK
3. **Python** - Initial protobuf analysis
4. **Hex editors** - Raw packet examination

### Test Procedure

1. Force-stop Garmin Explore
2. Revoke Explore's Bluetooth permission
3. Connect test app (GdogTAK or nRF Connect)
4. Subscribe to all notify characteristics
5. Walk with collar to generate position changes
6. Analyze received packets

### Observed Behavior

- Without Explore: Only 2-byte heartbeat packets
- With Explore: 180+ byte position packets every 2-5 seconds
- Position updates tied to collar movement

---

## Code Reference

### Kotlin Parser (simplified)

```kotlin
fun parsePosition(data: ByteArray): Position? {
    // Find coordinate signature
    val sigIndex = findSequence(data, byteArrayOf(0x0A, 0x0C, 0x08))
    if (sigIndex < 0) return null
    
    // Parse latitude varint
    var offset = sigIndex + 3
    val (latSemi, latLen) = parseVarint(data, offset)
    offset += latLen
    
    // Skip to longitude (field marker 0x10)
    if (data[offset] != 0x10.toByte()) return null
    offset++
    
    // Parse longitude varint
    val (lonSemi, _) = parseVarint(data, offset)
    
    // Convert to degrees
    val lat = semicirclesToDegrees(latSemi)
    val lon = semicirclesToDegrees(lonSemi)
    
    // Find device type
    val isCollar = data.contains(0x35.toByte())
    
    return Position(lat, lon, isCollar)
}
```

---

## Open Questions

1. **Init command**: What does Explore write to start position streaming?
2. **Multiple dogs**: How are multiple collars distinguished in packets?
3. **Dog names**: Are collar names transmitted or just IDs?
4. **Status flags**: How to decode treed, on point, tracking status?
5. **Other models**: Do Alpha 200i, 100 use same protocol?

---

## Contributing

Help wanted:
- **btsnoop captures** showing Explore's init command
- **Testing** with other Alpha/collar models
- **Protocol decoding** for remaining packet types

File issues/PRs at: https://github.com/mighkel/GdogTAK

---

## References

- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) - Garmin BLE protocol work
- [Garmin FIT SDK](https://developer.garmin.com/fit/protocol/) - Semicircle documentation
- [Protocol Buffers](https://developers.google.com/protocol-buffers) - Encoding reference
- [Bluetooth GATT](https://www.bluetooth.com/specifications/gatt/) - BLE fundamentals
