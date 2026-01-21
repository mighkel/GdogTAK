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
}
