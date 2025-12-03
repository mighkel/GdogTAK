package com.gdogtak.ble

/**
 * Garmin Multi-Link BLE Protocol Parser
 *
 * Decodes dog collar and handheld position data from Garmin Alpha
 * BLE notifications on characteristic 6a4e2813-667b-11e3-949a-0800200c9a66
 *
 * Protocol findings:
 * - Device marker 0x35 = dog collar, 0x28 = handheld
 * - Coordinates encoded as Garmin semicircles in protobuf varints
 * - Pattern: 0A 0C 08 [lat varint] 10 [lon varint] 18 [timestamp]
 */
object GarminProtocol {

    // Garmin Multi-Link Service and Characteristic UUIDs
    const val SERVICE_UUID = "6a4e2800-667b-11e3-949a-0800200c9a66"
    const val NOTIFY_CHAR_UUID = "6a4e2813-667b-11e3-949a-0800200c9a66"

    // Device type markers
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
        if (data.size < 40) return null

        // Determine device type
        val isCollar = isCollarMessage(data)
        val isHandheld = isHandheldMessage(data)

        if (!isCollar && !isHandheld) return null

        // Find and decode coordinates
        val coords = findCoordinates(data) ?: return null

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
     * Look for device marker pattern: 02 XX 01
     */
    private fun findDeviceMarker(data: ByteArray, marker: Byte): Boolean {
        val searchLimit = minOf(data.size - 3, 25)
        for (i in 0 until searchLimit) {
            if (data[i] == 0x02.toByte() &&
                data[i + 1] == marker &&
                data[i + 2] == 0x01.toByte()) {
                return true
            }
        }
        return false
    }

    /**
     * Find coordinate block (0A 0C 08) and decode lat/lon
     */
    private fun findCoordinates(data: ByteArray): Pair<Double, Double>? {
        for (i in 0 until data.size - 15) {
            // Look for coordinate block signature
            if (data[i] == 0x0A.toByte() &&
                data[i + 1] == 0x0C.toByte() &&
                data[i + 2] == 0x08.toByte()) {

                // Decode latitude varint
                val (latVarint, latLen) = decodeVarint(data, i + 3)
                val latSigned = zigzagDecode(latVarint)

                // Check for longitude marker (0x10)
                val lonOffset = i + 3 + latLen
                if (lonOffset >= data.size || data[lonOffset] != 0x10.toByte()) {
                    continue
                }

                // Decode longitude varint
                val (lonVarint, _) = decodeVarint(data, lonOffset + 1)
                val lonSigned = zigzagDecode(lonVarint)

                // Convert semicircles to degrees
                val lat = semicirclesToDegrees(latSigned)
                val lon = semicirclesToDegrees(lonSigned)

                // Sanity check
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    return Pair(lat, lon)
                }
            }
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

        while (offset + consumed < data.size) {
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
