package com.gdogtak.tak

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * Broadcasts CoT messages to ATAK via UDP multicast
 *
 * ATAK listens on multicast address 239.2.3.1 port 6969 by default.
 * This allows any app on the local network to send positions to ATAK
 * without needing to configure the TAK Server connection.
 */
class AtakBroadcaster(private val context: Context) {

    companion object {
        private const val TAG = "AtakBroadcaster"

        // ATAK default multicast address and port
        const val MULTICAST_ADDRESS = "239.2.3.1"
        const val MULTICAST_PORT = 6969

        // Alternative: SA multicast (used by some TAK configurations)
        const val SA_MULTICAST_ADDRESS = "239.2.3.1"
        const val SA_MULTICAST_PORT = 6969
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: DatagramSocket? = null
    private var multicastGroup: InetAddress? = null

    /**
     * Initialize the broadcaster
     * Must be called before sending any messages
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Acquire multicast lock (required on Android to receive/send multicast)
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("GdogTAK").apply {
                setReferenceCounted(true)
                acquire()
            }

            // Resolve multicast address
            multicastGroup = InetAddress.getByName(MULTICAST_ADDRESS)

            // Create UDP socket
            socket = DatagramSocket()

            Log.i(TAG, "Broadcaster initialized: $MULTICAST_ADDRESS:$MULTICAST_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize broadcaster", e)
            false
        }
    }

    /**
     * Send a CoT message to ATAK
     *
     * @param cotXml The complete CoT XML string
     * @return true if sent successfully
     */
    suspend fun sendCot(cotXml: String): Boolean = withContext(Dispatchers.IO) {
        val currentSocket = socket
        val group = multicastGroup

        if (currentSocket == null || group == null) {
            Log.w(TAG, "Broadcaster not initialized")
            return@withContext false
        }

        try {
            val data = cotXml.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                data,
                data.size,
                group,
                MULTICAST_PORT
            )

            currentSocket.send(packet)
            Log.d(TAG, "Sent CoT (${data.size} bytes) to $MULTICAST_ADDRESS:$MULTICAST_PORT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send CoT", e)
            false
        }
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        try {
            socket?.close()
            socket = null

            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            multicastLock = null

            Log.i(TAG, "Broadcaster shut down")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }
}
