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

    // Control characteristic for "app connected" signal - Alpha writes 02 00 here FIRST
    // This is the first characteristic in the service (handle 0x000D in btsnoop)
    // Writing 02 00 signals the Alpha that a valid app has connected
    const val CONTROL_INIT_CHAR_UUID = "6a4e2801-667b-11e3-949a-0800200c9a66"  // App connect signal

    // Write characteristics - based on btsnoop analysis of working Alpha app session:
    // - Alpha app sends INIT sequence to 6a4e2824 (handle 0x0029)
    // - Alpha app sends 02_35 POLLING to 6a4e2821 (handle 0x001A)
    // GdogTAK uses char1 for init and char2 for polling (matching Alpha's pattern)
    const val WRITE_CHAR_UUID = "6a4e2824-667b-11e3-949a-0800200c9a66"  // PRIMARY - Init sequence (handle 0x29)
    const val WRITE_CHAR_UUID_2 = "6a4e2821-667b-11e3-949a-0800200c9a66"  // SECONDARY - Polling/relay (handle 0x1A)
    
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
    private const val DEVICE_CONTACT: Byte = 0x33  // Other Alpha handhelds seen via VHF

    /**
     * Device types that can appear in BLE notifications
     */
    enum class DeviceType {
        COLLAR,     // Dog collar (0x35) - local, directly paired
        HANDHELD,   // Local Alpha handheld (0x28)
        CONTACT     // Remote Alpha handheld seen via VHF (0x33)
    }

    /**
     * Parsed position data from a BLE notification
     */
    data class DogPosition(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val isCollar: Boolean,
        val rawTimestamp: Long = 0,
        val deviceType: DeviceType = if (isCollar) DeviceType.COLLAR else DeviceType.HANDHELD
    )

    /**
     * A collar device entry extracted from the 229-byte device registry (command 07_16)
     */
    data class CollarRegistryEntry(
        val deviceId: ByteArray,   // 4-byte collar device ID (e.g., 33-91-77-CD)
        val fullEntry: ByteArray   // Full 16-byte entry for building relay commands
    ) {
        fun deviceIdHex(): String = deviceId.joinToString("-") { "%02X".format(it) }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CollarRegistryEntry) return false
            return deviceId.contentEquals(other.deviceId)
        }
        override fun hashCode(): Int = deviceId.contentHashCode()
    }
    
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
        val isContact = isContactMessage(data)

        android.util.Log.i("GarminProtocol", "Device type: collar=$isCollar, handheld=$isHandheld, contact=$isContact")

        if (!isCollar && !isHandheld && !isContact) {
            android.util.Log.i("GarminProtocol", "No device marker found (02 35, 02 28, or 02 33)")
            return null
        }

        // Find and decode coordinates
        val coords = findCoordinates(data)
        if (coords == null) {
            android.util.Log.i("GarminProtocol", "No coordinates found in packet")
            return null
        }

        val deviceType = when {
            isCollar -> DeviceType.COLLAR
            isContact -> DeviceType.CONTACT
            else -> DeviceType.HANDHELD
        }

        android.util.Log.i("GarminProtocol", ">>> COORDS PARSED: lat=${coords.first}, lon=${coords.second}, type=$deviceType")

        return DogPosition(
            latitude = coords.first,
            longitude = coords.second,
            timestamp = System.currentTimeMillis(),
            isCollar = isCollar,
            deviceType = deviceType
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
     * Check if this message contains contact (remote Alpha) position data
     */
    private fun isContactMessage(data: ByteArray): Boolean {
        return findDeviceMarker(data, DEVICE_CONTACT)
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
     * Find coordinate block and decode lat/lon
     *
     * Pattern: 0A [length] 08 [lat varint] 10 [lon varint]
     * where [length] varies (0x0C=12, 0x1A=26, etc.)
     *
     * The coordinate block starts with:
     * - 0A = protobuf field 1, length-delimited
     * - XX = length byte (varies)
     * - 08 = field 1 (latitude), varint wire type
     */
    private fun findCoordinates(data: ByteArray): Pair<Double, Double>? {
        for (i in 0 until data.size - 15) {
            // Look for coordinate block signature: 0A [any length] 08
            // The length byte (data[i+1]) can vary: 0x0C (12), 0x1A (26), etc.
            if (data[i] == 0x0A.toByte() &&
                data[i + 2] == 0x08.toByte()) {

                val lengthByte = data[i + 1].toInt() and 0xFF
                // Sanity check: length should be reasonable (8-50 bytes for coordinates)
                if (lengthByte in 8..50) {
                    android.util.Log.d("GarminProtocol", "Found 0A ${"%02X".format(lengthByte)} 08 at index $i")
                    val result = tryDecodeCoordinates(data, i + 3)
                    if (result != null) return result
                }
            }

            // Also check for nested pattern: 0A XX 0A YY 08
            if (i + 4 < data.size &&
                data[i] == 0x0A.toByte() &&
                data[i + 2] == 0x0A.toByte() &&
                data[i + 4] == 0x08.toByte()) {

                val outerLen = data[i + 1].toInt() and 0xFF
                val innerLen = data[i + 3].toInt() and 0xFF
                if (outerLen in 8..100 && innerLen in 8..50) {
                    android.util.Log.d("GarminProtocol", "Found nested 0A ${"%02X".format(outerLen)} 0A ${"%02X".format(innerLen)} 08 at index $i")
                    val result = tryDecodeCoordinates(data, i + 5)
                    if (result != null) return result
                }
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
     * Parse the 229-byte device registry notification (command 07_16) to extract
     * registered collar device IDs.
     *
     * The registry contains collar entries in the format:
     *   0A 10 [16-byte device entry]
     * where the first 4 bytes of the entry are the collar device ID.
     *
     * @param data Raw bytes from BLE notification (229 bytes, starts with DE XX 00 07 16)
     * @return List of collar device entries found, empty if none
     */
    fun parseDeviceRegistry(data: ByteArray): List<CollarRegistryEntry> {
        val entries = mutableListOf<CollarRegistryEntry>()

        // Verify this is a device registry notification (command 07 16)
        if (data.size < 50) return entries
        if (data[3] != 0x07.toByte() || data[4] != 0x16.toByte()) {
            android.util.Log.d("GarminProtocol", "Not a device registry (07_16) packet")
            return entries
        }

        // Search for 0A 10 pattern (field 1, length 16 = device entry)
        for (i in 0 until data.size - 17) {
            if (data[i] == 0x0A.toByte() && data[i + 1] == 0x10.toByte()) {
                val entryData = data.sliceArray(i + 2 until i + 18)
                val deviceId = entryData.sliceArray(0 until 4)

                // Sanity check: device ID should have non-trivial bytes
                if (deviceId.any { it != 0x00.toByte() && it != 0x01.toByte() }) {
                    val entry = CollarRegistryEntry(deviceId, entryData)
                    if (!entries.any { it.deviceId.contentEquals(deviceId) }) {
                        entries.add(entry)
                        android.util.Log.i("GarminProtocol",
                            ">>> REGISTRY: Found collar device ID: ${entry.deviceIdHex()}")
                    }
                }
            }
        }

        android.util.Log.i("GarminProtocol",
            "Device registry parsed: ${entries.size} collar(s) found")
        return entries
    }

    /**
     * Check if a notification is a device registry packet (command 07_16).
     * These are 229 bytes and sent every ~20 seconds by the Alpha.
     */
    fun isDeviceRegistryPacket(data: ByteArray): Boolean {
        return data.size > 50 &&
               data[3] == 0x07.toByte() &&
               data[4] == 0x16.toByte()
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
