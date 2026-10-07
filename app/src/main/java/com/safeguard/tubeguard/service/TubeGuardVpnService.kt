package com.safeguard.tubeguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.safeguard.tubeguard.MainActivity
import com.safeguard.tubeguard.R
import com.safeguard.tubeguard.util.PrefsManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

class TubeGuardVpnService : VpnService() {

    companion object {
        private const val TAG = "TubeGuardVPN"
        const val ACTION_START = "com.safeguard.tubeguard.START_VPN"
        const val ACTION_STOP = "com.safeguard.tubeguard.STOP_VPN"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tubeguard_vpn_channel"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val DNS_SERVER = "8.8.8.8"

        // All YouTube-related domains to block
        val BLOCKED_DOMAINS = setOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "www.youtube-nocookie.com",
            "googlevideo.com",
            "r1---sn-vgqs7ne6.googlevideo.com",
            "ytimg.com",
            "i.ytimg.com",
            "s.ytimg.com",
            "yt3.ggpht.com",
            "youtubei.googleapis.com",
            "www.googleapis.com",
            "accounts.youtube.com",
            "studio.youtube.com",
            "music.youtube.com"
        )

        // YouTube IP ranges in CIDR notation (stored as prefix/mask pairs for matching)
        val BLOCKED_IP_RANGES = listOf(
            Pair(ipToLong("208.65.153.0"), 24),
            Pair(ipToLong("208.65.154.0"), 24),
            Pair(ipToLong("208.65.155.0"), 24),
            Pair(ipToLong("208.117.254.0"), 24),
            Pair(ipToLong("173.194.0.0"), 16),
            Pair(ipToLong("216.58.192.0"), 19)
        )

        private fun ipToLong(ip: String): Long {
            val parts = ip.split(".")
            var result = 0L
            for (part in parts) {
                result = result shl 8
                result = result or part.toLong()
            }
            return result
        }

        fun isBlockedIp(ip: Long): Boolean {
            for ((prefix, maskBits) in BLOCKED_IP_RANGES) {
                val mask = if (maskBits == 0) 0L else (0xFFFFFFFFL shl (32 - maskBits)) and 0xFFFFFFFFL
                if ((ip and mask) == (prefix and mask)) return true
            }
            return false
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var vpnThread: Thread? = null
    private lateinit var prefsManager: PrefsManager

    override fun onCreate() {
        super.onCreate()
        prefsManager = PrefsManager.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                startVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning.get()) return

        try {
            val builder = Builder()
            builder.setSession("TubeGuard")
            builder.addAddress(VPN_ADDRESS, 32)
            builder.addDnsServer(DNS_SERVER)
            // Only route DNS server traffic through VPN — all other traffic bypasses VPN
            // This lets us inspect/block DNS queries without disrupting regular traffic
            builder.addRoute(DNS_SERVER, 32)
            builder.setMtu(1500)
            builder.setBlocking(true)

            // Allow our own app to bypass VPN to avoid recursion
            builder.addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            isRunning.set(true)
            prefsManager.vpnEnabled = true

            vpnThread = Thread({
                runVpnLoop()
            }, "TubeGuardVpnThread").also { it.start() }

            Log.i(TAG, "VPN started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
        }
    }

    private fun stopVpn() {
        isRunning.set(false)
        vpnThread?.interrupt()
        vpnThread = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null

        prefsManager.vpnEnabled = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    private fun runVpnLoop() {
        val vpnFd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(vpnFd)
        val outputStream = FileOutputStream(vpnFd)

        // Buffer for reading packets - MTU size
        val packetBuffer = ByteArray(32767)
        val buffer = ByteBuffer.allocate(32767)

        Log.i(TAG, "VPN loop started")

        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                buffer.clear()
                val length = inputStream.read(packetBuffer)
                if (length <= 0) continue

                buffer.put(packetBuffer, 0, length)
                buffer.flip()

                // Process the IP packet
                val response = processPacket(packetBuffer, length)
                if (response != null) {
                    outputStream.write(response)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error in VPN loop: ${e.message}")
                }
            }
        }

        try {
            inputStream.close()
            outputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing streams: ${e.message}")
        }

        Log.i(TAG, "VPN loop ended")
    }

    /**
     * Process a raw IP packet. If it's a DNS query for a blocked domain, return
     * a spoofed NXDOMAIN response. Otherwise return null (let the packet pass).
     */
    private fun processPacket(packet: ByteArray, length: Int): ByteArray? {
        if (length < 20) return null

        // Check IP version (first nibble of first byte)
        val ipVersion = (packet[0].toInt() and 0xF0) ushr 4
        if (ipVersion != 4) return null // Only handle IPv4

        // IP header length (lower nibble of first byte) * 4
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (length < ipHeaderLength + 8) return null

        // Protocol field (offset 9) - 17 = UDP
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // Only interested in UDP

        // Extract destination port (bytes ipHeaderLength+2 and ipHeaderLength+3)
        val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)

        if (destPort != 53) return null // Only interested in DNS (port 53)

        // Extract source IP (bytes 12-15) and dest IP (bytes 16-19)
        val srcIp = extractIp(packet, 12)
        val dstIp = extractIp(packet, 16)

        // Check if the destination IP is a blocked YouTube IP
        val dstIpLong = ipToLong(formatIp(packet, 16))
        if (isBlockedIp(dstIpLong)) {
            Log.d(TAG, "Blocked IP packet to: ${formatIp(packet, 16)}")
            return null // Drop the packet
        }

        // UDP payload starts at ipHeaderLength + 8
        val udpHeaderLength = 8
        val dnsOffset = ipHeaderLength + udpHeaderLength
        if (length <= dnsOffset) return null

        val dnsPayload = packet.copyOfRange(dnsOffset, length)

        // Parse the DNS query
        val queryDomain = parseDnsQuery(dnsPayload) ?: return null
        val normalizedDomain = queryDomain.lowercase().trimEnd('.')

        Log.d(TAG, "DNS query for: $normalizedDomain")

        val srcPort = ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 1].toInt() and 0xFF)

        // Check if domain should be blocked
        if (isDomainBlocked(normalizedDomain)) {
            Log.i(TAG, "Blocking DNS query for: $normalizedDomain")
            val dnsResponse = buildNxdomainResponse(dnsPayload)
            return buildIpUdpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = 53,
                dstPort = srcPort,
                payload = dnsResponse
            )
        }

        // Forward non-blocked DNS query to real DNS server via protected socket
        val dnsResponse = forwardDnsQuery(dnsPayload) ?: return null
        return buildIpUdpPacket(
            srcIp = dstIp,
            dstIp = srcIp,
            srcPort = 53,
            dstPort = srcPort,
            payload = dnsResponse
        )
    }

    private fun forwardDnsQuery(dnsPayload: ByteArray): ByteArray? {
        return try {
            val socket = DatagramSocket()
            protect(socket) // Bypass VPN to reach real DNS server
            socket.soTimeout = 3000
            val dnsServer = InetAddress.getByName(DNS_SERVER)
            socket.send(DatagramPacket(dnsPayload, dnsPayload.size, dnsServer, 53))
            val buf = ByteArray(512)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            socket.close()
            buf.copyOf(response.length)
        } catch (e: Exception) {
            Log.e(TAG, "DNS forward failed: ${e.message}")
            null
        }
    }

    private fun isDomainBlocked(domain: String): Boolean {
        if (BLOCKED_DOMAINS.contains(domain)) return true
        // Check if it's a subdomain of any blocked domain
        for (blocked in BLOCKED_DOMAINS) {
            if (domain.endsWith(".$blocked")) return true
        }
        return false
    }

    private fun extractIp(packet: ByteArray, offset: Int): ByteArray {
        return byteArrayOf(packet[offset], packet[offset + 1], packet[offset + 2], packet[offset + 3])
    }

    private fun formatIp(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}" +
                ".${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
    }

    /**
     * Parse the domain name from a DNS query packet.
     * DNS format: labels separated by length bytes, terminated by 0x00
     */
    private fun parseDnsQuery(dns: ByteArray): String? {
        if (dns.size < 12) return null

        // DNS header is 12 bytes; question section starts at offset 12
        var offset = 12
        val labels = mutableListOf<String>()

        while (offset < dns.size) {
            val labelLength = dns[offset].toInt() and 0xFF
            if (labelLength == 0) break
            offset++
            if (offset + labelLength > dns.size) return null
            labels.add(String(dns, offset, labelLength, Charsets.US_ASCII))
            offset += labelLength
        }

        return if (labels.isEmpty()) null else labels.joinToString(".")
    }

    /**
     * Build a DNS NXDOMAIN response from a query packet.
     * Copies the transaction ID and question, sets RCODE to 3 (NXDOMAIN).
     */
    private fun buildNxdomainResponse(query: ByteArray): ByteArray {
        val response = query.copyOf()
        if (response.size < 4) return response

        // Set QR bit (bit 15 of flags) = 1 (response)
        // Set AA bit = 1 (authoritative)
        // Set RCODE = 3 (NXDOMAIN)
        // Flags are at bytes 2-3
        val flags = 0x8183.toShort() // QR=1, OPCODE=0, AA=1, TC=0, RD=1, RA=1, RCODE=3
        response[2] = (flags.toInt() ushr 8).toByte()
        response[3] = (flags.toInt() and 0xFF).toByte()

        // Zero out answer count, authority count, additional count
        // (bytes 6-7=ANCOUNT, 8-9=NSCOUNT, 10-11=ARCOUNT)
        response[6] = 0; response[7] = 0
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0

        // Trim to just header + question section (no answer records)
        var end = 12
        while (end < response.size) {
            val len = response[end].toInt() and 0xFF
            if (len == 0) { end += 5; break } // 0x00 + QTYPE(2) + QCLASS(2)
            end += len + 1
        }

        return if (end <= response.size) response.copyOf(end) else response
    }

    /**
     * Build a raw IPv4 + UDP packet carrying the given payload.
     */
    private fun buildIpUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val ipLength = 20 + udpLength
        val packet = ByteArray(ipLength)

        // IPv4 header
        packet[0] = 0x45.toByte()         // Version=4, IHL=5
        packet[1] = 0x00.toByte()         // DSCP/ECN
        packet[2] = (ipLength ushr 8).toByte()
        packet[3] = (ipLength and 0xFF).toByte()
        packet[4] = 0x00; packet[5] = 0x00  // Identification
        packet[6] = 0x40; packet[7] = 0x00  // Flags=DF, Fragment offset=0
        packet[8] = 0x40.toByte()            // TTL=64
        packet[9] = 0x11.toByte()            // Protocol=UDP
        packet[10] = 0x00; packet[11] = 0x00 // Header checksum (will compute)
        srcIp.copyInto(packet, 12)
        dstIp.copyInto(packet, 16)

        // Compute IP header checksum
        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = (ipChecksum ushr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP header
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (dstPort ushr 8).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = (udpLength ushr 8).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        packet[26] = 0x00; packet[27] = 0x00 // UDP checksum (optional, zero = disabled)

        // Copy DNS payload
        payload.copyInto(packet, 28)

        return packet
    }

    /**
     * Compute Internet checksum (one's complement sum of 16-bit words).
     */
    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((length % 2) != 0) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TubeGuard VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "TubeGuard is actively blocking YouTube"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TubeGuardVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TubeGuard Active")
            .setContentText("YouTube is blocked")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Open App", pendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
