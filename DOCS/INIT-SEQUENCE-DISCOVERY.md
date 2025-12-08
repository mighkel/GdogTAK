# Garmin Alpha BLE Init Sequence Discovery

**Date**: December 8, 2024  
**Source**: btsnoop capture from Moto G 5G running Garmin Explore  
**Device**: Alpha 300i (FA:1A:C1:B3:DC:2F)

## Summary

We've captured the exact BLE commands that Garmin Explore sends to trigger position data streaming from the Alpha 300i. This enables GdogTAK to operate standalone without requiring Garmin Explore to run in the background.

## Characteristics

| UUID | Handle | Direction | Purpose |
|------|--------|-----------|---------|
| `6a4e2821` | 0x0024 | Write | Send commands to Alpha |
| `6a4e2811` | 0x0021 | Notify | Receive data from Alpha |

## Init Sequence

### Step 1: Device ID Exchange

Write a 12-byte device identifier:

```
00 05 [8-byte-device-id] 00 00
```

Example from capture:
```
00 05 8d 3d b0 e5 92 59 03 3d 00 00
```

Wait for response notification, then write:

```
00 00 [8-byte-device-id] 04 00 00
```

Example:
```
00 00 8d 3d b0 e5 92 59 03 3d 04 00 00
```

The 8-byte device ID appears to be unique per phone/session. It may be:
- Derived from Android ID
- A random session identifier
- A fixed Garmin account identifier

### Step 2: Enable Data Channels

Send 5 channel enable commands (with ~100ms delay between each):

```
15 00  (channel 0)
15 01  (channel 1)
15 02  (channel 2)
15 03  (channel 3)
15 04  (channel 4)
```

Each command triggers a response notification. These likely correspond to:
- Channel 0: Device info
- Channel 1: Status/heartbeat
- Channel 2: Config
- Channel 3: Position data (dog collars)
- Channel 4: Position data (handhelds)

### Step 3: Start Streaming

```
16 01 19 00 00 00
```

This triggers the Alpha to begin streaming position updates.

## Full Sequence Timing (from capture)

```
Time      Command
16.847s   00 05 8d 3d b0 e5 92 59 03 3d 00 00  (device ID init)
16.944s   00 00 8d 3d b0 e5 92 59 03 3d 04 00 00  (device ID confirm)
17.045s   15 00  (enable channel 0)
17.209s   15 01  (enable channel 1)
17.298s   15 02  (enable channel 2)
17.388s   15 03  (enable channel 3)
17.523s   15 04  (enable channel 4)
17.815s   16 01 19 00 00 00  (start streaming)
18.045s   [device info exchange begins]
19.xxx    [position data starts flowing]
```

## Position Data Format

After init, position packets arrive on handle 0x0021 with format:
```
17 00 02 [length] [type] [seq] [data...] [checksum]
```

Position data contains:
- Device type: `02 35` = collar, `02 28` = handheld
- Coordinate block signature: `0A 0C 08`
- Coordinates: Protobuf varints in Garmin semicircles

## Implementation Notes

### Generating Device ID

Options:
1. Use a fixed/random 8-byte ID (simplest)
2. Derive from Android ID: `Settings.Secure.ANDROID_ID`
3. Generate UUID and take first 8 bytes

### Error Handling

- If no response after device ID write, retry
- If channel enable fails, continue with next channel
- If no position data after start streaming, re-send init sequence

### BLE Write Type

Use Write Command (no response) for most commands:
```kotlin
characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
```

## Kotlin Code

```kotlin
object GarminInitSequence {
    // Generate or use fixed device ID
    private val deviceId = byteArrayOf(
        0x8d.toByte(), 0x3d.toByte(), 0xb0.toByte(), 0xe5.toByte(),
        0x92.toByte(), 0x59.toByte(), 0x03.toByte(), 0x3d.toByte()
    )
    
    fun getInitPacket1(): ByteArray {
        return byteArrayOf(0x00, 0x05) + deviceId + byteArrayOf(0x00, 0x00)
    }
    
    fun getInitPacket2(): ByteArray {
        return byteArrayOf(0x00, 0x00) + deviceId + byteArrayOf(0x04, 0x00, 0x00)
    }
    
    fun getChannelEnable(channel: Int): ByteArray {
        return byteArrayOf(0x15, channel.toByte())
    }
    
    fun getStartStreaming(): ByteArray {
        return byteArrayOf(0x16, 0x01, 0x19, 0x00, 0x00, 0x00)
    }
}
```

## Verification

Position data confirmed in btsnoop frame 711:
```
0a 0c 08 80 b0 d7 f1 03 10 ff eb f7 a7 0a 18 90 80 e8 9c 04
         ^^^^^^^^^^^^^^^^    ^^^^^^^^^^^^^^^^    ^^^^^^^^^^^^
         latitude varint     longitude varint    altitude?
```

Decoded:
- Latitude: ~43.72° N (Idaho)
- Longitude: ~-116.01° W (Idaho)

## Next Steps

1. Update `BleTrackingService.kt` to send init sequence after CCCD writes
2. Test standalone operation without Garmin Explore
3. Experiment with device ID generation
4. Document any error responses and recovery
