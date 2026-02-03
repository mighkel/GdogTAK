package com.gdogtak.ble

/**
 * Garmin Multi-Link BLE Protocol Parser
 * 
 * Decodes dog collar and handheld position data from Garmin Alpha
 * BLE notifications on characteristics 6a4e2810-2814
 * 
 * Protocol findings (from btsnoop capture):
 * - Write init commands to 6a4e2821
 * - Receive data on 6a4e2811 (primary) and 6a4e2810-2814
 * - Device marker 0x35 = dog collar, 0x28 = handheld
 * - Coordinates encoded as Garmin semicircles in protobuf varints
 * - Pattern: 0A 0C 08 [lat varint] 10 [lon varint] 18 [timestamp]
 */
object GarminProtocol {
    
    // Garmin Multi-Link Service UUID
    const val SERVICE_UUID = "6a4e2800-667b-11e3-949a-0800200c9a66"
    
    // Write characteristic for init sequence commands
    const val WRITE_CHAR_UUID = "6a4e2823-667b-11e3-949a-0800200c9a66"  // Channel 3 - COLLAR DATA (handle 0x24)
    const val WRITE_CHAR_UUID_2 = "6a4e2824-667b-11e3-949a-0800200c9a66"  // Channel 4 - also has collar data (handle 0x29)
    
    // All notification characteristics to subscribe to
    val NOTIFY_CHAR_UUIDS = listOf(
        "6a4e2810-667b-11e3-949a-0800200c9a66",
        "6a4e2811-667b-11e3-949a-0800200c9a66",  // Primary position data
        "6a4e2812-667b-11e3-949a-0800200c9a66",
        "6a4e2813-667b-11e3-949a-0800200c9a66",
        "6a4e2814-667b-11e3-949a-0800200c9a66"
    )
    
    // Legacy single notify UUID (for backward compatibility)
    const val NOTIFY_CHAR_UUID = "6a4e2811-667b-11e3-949a-0800200c9a66"
    
    // Device type markers in position packets
    private const val DEVICE_COLLAR: Byte = 0x35
    private const val DEVICE_HANDHELD: Byte = 0x28
    
    /**
     * Parsed position data from a BLE notification
     */
    data class DogPosition(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val isCollar: Boolean,
        val rawTimestamp: Long = 0
    )
    
    /**
     * Parse a BLE notification and extract position if present
     * 
     * @param data Raw bytes from BLE notification
     * @return DogPosition if valid position found, null otherwise
     */
    fun parseNotification(data: ByteArray): DogPosition? {
        if (data.size < 20) {
            android.util.Log.d("GarminProtocol", "Packet too small: ${data.size} bytes")
            return null
        }
        
        // Determine device type
        val isCollar = isCollarMessage(data)
        val isHandheld = isHandheldMessage(data)
        
        android.util.Log.d("GarminProtocol", "Device type: collar=$isCollar, handheld=$isHandheld")
        
        if (!isCollar && !isHandheld) {
            android.util.Log.d("GarminProtocol", "No device marker found (02 35 or 02 28)")
            return null
        }
        
        // Find and decode coordinates
        val coords = findCoordinates(data)
        if (coords == null) {
            android.util.Log.d("GarminProtocol", "No coordinates found in packet")
            return null
        }
        
        android.util.Log.i("GarminProtocol", ">>> COORDS PARSED: lat=${coords.first}, lon=${coords.second}, isCollar=$isCollar")
        
        return DogPosition(
            latitude = coords.first,
            longitude = coords.second,
            timestamp = System.currentTimeMillis(),
            isCollar = isCollar
        )
    }
    
    /**
     * Check if this message contains dog collar position data
     */
    private fun isCollarMessage(data: ByteArray): Boolean {
        return findDeviceMarker(data, DEVICE_COLLAR)
    }
    
    /**
     * Check if this message contains handheld position data
     */
    private fun isHandheldMessage(data: ByteArray): Boolean {
        return findDeviceMarker(data, DEVICE_HANDHELD)
    }
    
    /**
     * Look for device marker pattern: 02 XX 01 or just 02 XX anywhere
     */
    private fun findDeviceMarker(data: ByteArray, marker: Byte): Boolean {
        // Search for 02 XX pattern (device type indicator)
        val searchLimit = minOf(data.size - 2, 30)
        for (i in 0 until searchLimit) {
            if (data[i] == 0x02.toByte()) {
                val found = data[i + 1]
                if (found == marker) {
                    android.util.Log.d("GarminProtocol", "Found device marker 02 ${"%02x".format(marker)} at index $i")
                    return true
                }
            }
        }
        // Log what we DID find for debugging
        for (i in 0 until searchLimit) {
            if (data[i] == 0x02.toByte()) {
                android.util.Log.d("GarminProtocol", "Found 02 ${"%02x".format(data[i+1])} at index $i (looking for ${"%02x".format(marker)})")
            }
        }
        return false
    }
    
    /**
     * Find coordinate block (0A 0C 08 or 0A 0F 0A 0C 08) and decode lat/lon
     * 
     * Pattern variations found in btsnoop:
     * - Direct: 0A 0C 08 [lat] 10 [lon]
     * - Nested: 0A 2F 0A 0C 08 [lat] 10 [lon] (inside larger structure)
     */
    private fun findCoordinates(data: ByteArray): Pair<Double, Double>? {
        for (i in 0 until data.size - 15) {
            // Look for coordinate block signature: 0A 0C 08
            if (data[i] == 0x0A.toByte() && 
                data[i + 1] == 0x0C.toByte() && 
                data[i + 2] == 0x08.toByte()) {
                
                android.util.Log.d("GarminProtocol", "Found 0A 0C 08 at index $i")
                val result = tryDecodeCoordinates(data, i + 3)
                if (result != null) return result
            }
            
            // Also check for nested pattern: 0A XX 0A 0C 08
            if (i + 4 < data.size &&
                data[i] == 0x0A.toByte() &&
                data[i + 2] == 0x0A.toByte() &&
                data[i + 3] == 0x0C.toByte() &&
                data[i + 4] == 0x08.toByte()) {
                
                android.util.Log.d("GarminProtocol", "Found nested 0A XX 0A 0C 08 at index $i")
                val result = tryDecodeCoordinates(data, i + 5)
                if (result != null) return result
            }
        }
        android.util.Log.d("GarminProtocol", "No coordinate signature found in ${data.size} bytes")
        return null
    }
    
    /**
     * Try to decode coordinates starting at given offset
     */
    private fun tryDecodeCoordinates(data: ByteArray, offset: Int): Pair<Double, Double>? {
        if (offset >= data.size - 5) return null
        
        // Decode latitude varint
        val (latVarint, latLen) = decodeVarint(data, offset)
        val latSigned = zigzagDecode(latVarint)
        
        // Check for longitude marker (0x10)
        val lonOffset = offset + latLen
        if (lonOffset >= data.size || data[lonOffset] != 0x10.toByte()) {
            return null
        }
        
        // Decode longitude varint
        val (lonVarint, _) = decodeVarint(data, lonOffset + 1)
        val lonSigned = zigzagDecode(lonVarint)
        
        // Convert semicircles to degrees
        val lat = semicirclesToDegrees(latSigned)
        val lon = semicirclesToDegrees(lonSigned)
        
        // Sanity check - valid coordinates
        if (lat in -90.0..90.0 && lon in -180.0..180.0 && 
            (lat != 0.0 || lon != 0.0)) {  // Skip null island
            return Pair(lat, lon)
        }
        
        return null
    }
    
    /**
     * Decode a protobuf varint from byte array
     * 
     * @return Pair of (value, bytes consumed)
     */
    private fun decodeVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var consumed = 0
        
        while (offset + consumed < data.size && consumed < 10) {
            val byte = data[offset + consumed].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            consumed++
            
            if ((byte and 0x80) == 0) {
                break
            }
            shift += 7
        }
        
        return Pair(result, consumed)
    }
    
    /**
     * ZigZag decode for signed integers
     * Protobuf encodes negative numbers using ZigZag encoding
     */
    private fun zigzagDecode(value: Long): Long {
        return (value ushr 1) xor -(value and 1)
    }
    
    /**
     * Convert Garmin semicircles to decimal degrees
     * Semicircles = degrees * (2^31 / 180)
     */
    private fun semicirclesToDegrees(semicircles: Long): Double {
        return semicircles * (180.0 / 2147483648.0)
    }

    /**
     * Compute the 2-byte checksum for a 02 11 collar slot registration command.
     *
     * Reverse-engineered from 175 known-good packets in the Dec 8, 2024 btsnoop
     * capture via GF(2) linear algebra. The checksum is a base constant XOR'd
     * with bit-position-specific masks depending on the message content.
     *
     * @param msg The 16-byte message body (02 11 ... 03), NOT including the
     *            checksum or trailing 00 terminator.
     * @return The 2-byte checksum as an Int (big-endian: high byte << 8 | low byte)
     */
    fun computeCollarSlotChecksum(msg: ByteArray): Int {
        require(msg.size == 16) { "02 11 message must be exactly 16 bytes, got ${msg.size}" }
        var chk = 0x83C2
        // Byte 4: slot (bits 4..0)
        if (msg[4].toInt() and 0x10 != 0) chk = chk xor 0xC1FF
        if (msg[4].toInt() and 0x08 != 0) chk = chk xor 0xE1DF
        if (msg[4].toInt() and 0x04 != 0) chk = chk xor 0xF1CF
        if (msg[4].toInt() and 0x02 != 0) chk = chk xor 0xF9C7
        if (msg[4].toInt() and 0x01 != 0) chk = chk xor 0xFDC3
        // Byte 5: b4/b3 flag (bit 2)
        if (msg[5].toInt() and 0x04 != 0) chk = chk xor 0x2114
        // Byte 7: seq high (bit 1)
        if (msg[7].toInt() and 0x02 != 0) chk = chk xor 0xC1CC
        // Byte 8: seq low (bits 6..0)
        if (msg[8].toInt() and 0x40 != 0) chk = chk xor 0x0430
        if (msg[8].toInt() and 0x20 != 0) chk = chk xor 0x0218
        if (msg[8].toInt() and 0x10 != 0) chk = chk xor 0x010C
        if (msg[8].toInt() and 0x08 != 0) chk = chk xor 0x01A6
        if (msg[8].toInt() and 0x04 != 0) chk = chk xor 0x01F3
        if (msg[8].toInt() and 0x02 != 0) chk = chk xor 0x81D9
        if (msg[8].toInt() and 0x01 != 0) chk = chk xor 0xC1CC
        // Byte 15: trailing marker (bit 1)
        if (msg[15].toInt() and 0x02 != 0) chk = chk xor 0x0200
        return chk and 0xFFFF
    }

    /**
     * Build a complete 02 11 collar slot registration packet with computed checksum.
     *
     * @param slot Collar slot number (0x80..0x9F)
     * @param seqHi Sequence counter high byte
     * @param seqLo Sequence counter low byte
     * @param b4Flag true for 0xB4 variant (seqHi typically 02), false for 0xB3 (seqHi typically 03)
     * @return Complete packet bytes including checksum and trailing 0x00
     */
    fun buildCollarSlotPacket(slot: Int, seqHi: Int, seqLo: Int, b4Flag: Boolean = true): ByteArray {
        val flagByte = if (b4Flag) 0xB4.toByte() else 0xB3.toByte()
        val midByte: Byte = if (b4Flag) 0x01 else 0x03
        val msg = byteArrayOf(
            0x02, 0x11, 0x01, 0x04,
            slot.toByte(),
            flagByte, 0x13,
            seqHi.toByte(), seqLo.toByte(),
            midByte, 0x01, 0x01, 0x01, 0x01, 0x01, 0x03
        )
        val chk = computeCollarSlotChecksum(msg)
        return msg + byteArrayOf((chk shr 8).toByte(), (chk and 0xFF).toByte(), 0x00)
    }
}
