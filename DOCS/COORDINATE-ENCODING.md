# Garmin Coordinate Encoding

How Garmin encodes GPS coordinates in BLE packets.

## Overview

Garmin uses **semicircles** to represent latitude and longitude, encoded as **protobuf varints** within BLE packets.

## Semicircles

A semicircle is 1/2³¹ of a full circle. This gives:
- Full circle = 2³¹ = 2,147,483,648 semicircles
- Half circle (180°) = 2³⁰ semicircles
- Each semicircle = 180° / 2³¹ ≈ 0.0000000838° ≈ 0.009mm at equator

This provides sub-centimeter precision while fitting in a signed 32-bit integer.

## Conversion Formulas

### Semicircles to Degrees

```
degrees = semicircles × (180 / 2³¹)
degrees = semicircles × (180 / 2147483648)
degrees = semicircles × 8.381903171539307e-8
```

### Degrees to Semicircles

```
semicircles = degrees × (2³¹ / 180)
semicircles = degrees × 11930464.711111111
```

## Protobuf Varint Encoding

Coordinates are stored as protobuf varints (variable-length integers):

### Encoding Rules

1. Each byte uses 7 bits for data, 1 bit (MSB) as continuation flag
2. MSB = 1 means more bytes follow
3. MSB = 0 means this is the last byte
4. Bytes are little-endian (LSB first)
5. Values are signed using ZigZag encoding for negative numbers

### Example: Decoding Latitude

Raw bytes: `80 BC D7 F1 03`

```
80 = 1000 0000 → data: 0000000, continue
BC = 1011 1100 → data: 0111100, continue  
D7 = 1101 0111 → data: 1010111, continue
F1 = 1111 0001 → data: 1110001, continue
03 = 0000 0011 → data: 0000011, stop

Reassemble (7 bits each, LSB first):
0000011 1110001 1010111 0111100 0000000
= 00000111110001101011101111000000000
= 1040415616 (decimal)

Convert to degrees:
1040415616 × (180 / 2147483648) = 43.7211°
```

### Example: Decoding Longitude (Negative)

Raw bytes: `FF EF F7 A7 0A`

```
FF = 1111 1111 → data: 1111111, continue
EF = 1110 1111 → data: 1101111, continue
F7 = 1111 0111 → data: 1110111, continue
A7 = 1010 0111 → data: 0100111, continue
0A = 0000 1010 → data: 0001010, stop

Reassemble:
0001010 0100111 1110111 1101111 1111111
= 2765455359 (unsigned)

For signed 32-bit: 2765455359 - 2³² = -1529511937

Convert to degrees:
-1529511937 × (180 / 2147483648) = -116.0158°
```

## Kotlin Implementation

```kotlin
fun parseVarint(data: ByteArray, startIndex: Int): Pair<Long, Int> {
    var result = 0L
    var shift = 0
    var index = startIndex
    
    while (index < data.size) {
        val byte = data[index].toInt() and 0xFF
        result = result or ((byte and 0x7F).toLong() shl shift)
        index++
        
        if ((byte and 0x80) == 0) break  // No continuation bit
        shift += 7
    }
    
    return Pair(result, index - startIndex)
}

fun semicirclesToDegrees(semicircles: Long): Double {
    // Handle signed 32-bit values
    val signed = if (semicircles > Int.MAX_VALUE) {
        semicircles - 0x100000000L
    } else {
        semicircles
    }
    return signed * (180.0 / 2147483648.0)
}
```

## Packet Structure

Within a position packet, coordinates appear after the signature `0A 0C 08`:

```
0A      Field 1, wire type 2 (length-delimited)
0C      Length = 12 bytes
08      Field 1 (latitude), wire type 0 (varint)
[lat]   Latitude varint (variable length)
10      Field 2 (longitude), wire type 0 (varint)  
[lon]   Longitude varint (variable length)
18      Field 3 (altitude?), wire type 0 (varint)
[alt]   Altitude varint
```

## Validation

Test coordinates from Boise, Idaho area:

| Semicircles (signed) | Degrees | Location |
|---------------------|---------|----------|
| 1040415616 | 43.7211° | Latitude |
| -1529511937 | -116.0158° | Longitude |

These match expected positions in the test area, confirming the encoding.

## Altitude

Altitude appears to use a similar encoding but the exact format (meters, feet, offset) is not yet confirmed. The field marker is `18` following the longitude.

## Accuracy/Precision

- Semicircle resolution: ~0.009mm at equator
- GPS receiver accuracy: typically 3-10 meters
- Displayed precision: 7 decimal places (~1cm) is reasonable

## References

- [Garmin FIT SDK](https://developer.garmin.com/fit/protocol/) — Semicircle documentation
- [Protocol Buffers Encoding](https://developers.google.com/protocol-buffers/docs/encoding) — Varint format
- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — Garmin protocol work
