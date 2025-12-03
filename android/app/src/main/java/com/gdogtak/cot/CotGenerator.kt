package com.gdogtak.cot

import com.gdogtak.ble.GarminProtocol.DogPosition
import java.text.SimpleDateFormat
import java.util.*

/**
 * Generates Cursor-on-Target (CoT) XML messages for TAK systems
 *
 * CoT is the standard message format for ATAK, WinTAK, and TAK Server.
 * Each dog position becomes a CoT "event" that appears on the map.
 */
object CotGenerator {

    // CoT type for friendly ground unit
    // a = atom (specific entity)
    // f = friendly
    // G = ground
    // U = unit
    // C = combat/civilian
    private const val DEFAULT_COT_TYPE = "a-f-G-U-C"

    // How long before the position goes "stale" (seconds)
    private const val STALE_SECONDS = 30

    // ISO 8601 date format for CoT timestamps
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Configuration for a tracked dog
     */
    data class DogConfig(
        val uid: String,           // Unique identifier (e.g., "GDOG-TT25-001")
        val callsign: String,      // Display name (e.g., "K9-ROVER")
        val team: String = "",     // Team/group name
        val cotType: String = DEFAULT_COT_TYPE
    )

    /**
     * Generate a CoT XML event for a dog position
     *
     * @param position The parsed position from BLE
     * @param config Dog configuration (callsign, UID, etc.)
     * @return Complete CoT XML string
     */
    fun generateCot(position: DogPosition, config: DogConfig): String {
        val now = Date(position.timestamp)
        val stale = Date(position.timestamp + (STALE_SECONDS * 1000))

        val timeStr = dateFormat.format(now)
        val staleStr = dateFormat.format(stale)

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("\n")
            append("""<event version="2.0" """)
            append("""uid="${escapeXml(config.uid)}" """)
            append("""type="${config.cotType}" """)
            append("""time="$timeStr" """)
            append("""start="$timeStr" """)
            append("""stale="$staleStr" """)
            append("""how="m-g">""")  // m-g = machine GPS
            append("\n")

            // Position point
            append("""    <point """)
            append("""lat="${String.format(Locale.US, "%.7f", position.latitude)}" """)
            append("""lon="${String.format(Locale.US, "%.7f", position.longitude)}" """)
            append("""hae="0" """)    // Height above ellipsoid (unknown, use 0)
            append("""ce="10.0" """)  // Circular error (10m estimate)
            append("""le="10.0"/>""") // Linear error
            append("\n")

            // Detail section
            append("""    <detail>""")
            append("\n")
            append("""        <contact callsign="${escapeXml(config.callsign)}"/>""")
            append("\n")
            append("""        <remarks>SAR K9 - GPS Collar</remarks>""")
            append("\n")

            // Team/group if specified
            if (config.team.isNotEmpty()) {
                append("""        <__group name="${escapeXml(config.team)}" role="K9 Unit"/>""")
                append("\n")
            }

            // Track info (placeholder - could add speed/heading later)
            append("""        <track course="0" speed="0"/>""")
            append("\n")

            // Precision location metadata
            append("""        <precisionlocation altsrc="GPS" geopointsrc="GPS"/>""")
            append("\n")

            append("""    </detail>""")
            append("\n")
            append("""</event>""")
        }
    }

    /**
     * Escape special XML characters
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
