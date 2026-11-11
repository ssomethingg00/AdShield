package com.example.adshield.vpn

import android.net.VpnService
import android.util.Log
import com.example.adshield.blocklist.BlocklistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Reads raw IP packets from the TUN file descriptor, identifies DNS queries (UDP port 53),
 * and either blocks them (returning NXDOMAIN) or forwards them to the upstream resolver.
 *
 * Uses a non-blocking channel architecture with concurrent workers to prevent
 * head-of-line blocking on slow upstream DNS queries.
 */
class DnsPacketProcessor(
    private val tunIn: FileInputStream,
    private val tunOut: FileOutputStream,
    private val blocklist: BlocklistManager,
    private val vpnService: VpnService? = null,
    private val upstreamDns: InetAddress = InetAddress.getByName("1.1.1.1"),
    private val upstreamPort: Int = 53,
) {

    companion object {
        private const val TAG = "DnsProcessor"
        private const val MTU = 1500
        private const val DNS_TIMEOUT_MS = 1_500
        private const val UDP_PROTO = 17
        private const val DNS_PORT = 53
        private const val WORKER_COUNT = 4

        // Upstream DNS servers with automatic fallback (Google primary, Cloudflare, Quad9)
        private val UPSTREAM_SERVERS = listOf(
            "8.8.8.8",
            "1.1.1.1",
            "8.8.4.4",
            "1.0.0.1",
            "9.9.9.9"
        )
    }

    @Volatile
    var isRunning = false
        private set

    private val incomingChannel = Channel<ByteArray>(capacity = 128)
    private val outgoingChannel = Channel<ByteArray>(capacity = 128)

    /**
     * Starts the non-blocking reader, worker pool, and writer coroutines within [scope].
     */
    fun start(scope: CoroutineScope) {
        isRunning = true
        Log.i(TAG, "Starting DNS packet processor with $WORKER_COUNT workers")

        // 1. Reader loop (reads raw packets from TUN)
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MTU)
            var consecutiveErrors = 0

            while (isActive && isRunning) {
                try {
                    val len = tunIn.read(buffer)
                    if (len < 0) {
                        Log.i(TAG, "TUN interface reached EOF; exiting reader")
                        break
                    }
                    if (len == 0) continue

                    consecutiveErrors = 0
                    incomingChannel.send(buffer.copyOf(len))
                } catch (e: Exception) {
                    if (!isRunning || !isActive) break
                    consecutiveErrors++
                    Log.e(TAG, "TUN read error #$consecutiveErrors: ${e.message}")
                    if (consecutiveErrors >= 5) {
                        Log.e(TAG, "5 consecutive read errors; stopping processor")
                        break
                    }
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            stop()
        }

        // 2. Worker pool (handles DNS queries concurrently)
        repeat(WORKER_COUNT) {
            scope.launch(Dispatchers.Default) {
                for (packetBytes in incomingChannel) {
                    if (!isActive || !isRunning) break
                    try {
                        processPacket(packetBytes)
                    } catch (e: Exception) {
                        Log.w(TAG, "Worker processing error: ${e.message}")
                    }
                }
            }
        }

        // 3. Writer loop (serializes writing responses back to TUN)
        scope.launch(Dispatchers.IO) {
            for (response in outgoingChannel) {
                if (!isActive || !isRunning) break
                try {
                    tunOut.write(response)
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "TUN write error: ${e.message}")
                }
            }
        }
    }

    /**
     * Blocking loop for standalone or synchronous execution.
     */
    fun run() {
        isRunning = true
        val buffer = ByteArray(MTU)
        var consecutiveErrors = 0
        Log.i(TAG, "DNS packet processor started (synchronous loop)")

        while (isRunning) {
            try {
                val len = tunIn.read(buffer)
                if (len < 0) {
                    Log.i(TAG, "TUN EOF — stopping")
                    break
                }
                if (len == 0) continue

                consecutiveErrors = 0
                val packetBytes = buffer.copyOf(len)
                processPacket(packetBytes)
            } catch (e: Exception) {
                if (!isRunning) break
                consecutiveErrors++
                Log.e(TAG, "TUN read error #$consecutiveErrors: ${e.message}")
                if (consecutiveErrors >= 5) {
                    Log.e(TAG, "5 consecutive errors — stopping processor")
                    break
                }
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        stop()
        Log.i(TAG, "DNS packet processor stopped")
    }

    fun stop() {
        isRunning = false
        incomingChannel.close()
        outgoingChannel.close()
    }

    // ─────────────────────────────────────────────────────────────
    // Packet Inspection
    // ─────────────────────────────────────────────────────────────

    private fun processPacket(packet: ByteArray) {
        val len = packet.size
        if (len < 20) return

        val version = (packet[0].toInt() and 0xF0) ushr 4
        if (version != 4) return // IPv4 only

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (len < ihl + 8) return

        val proto = packet[9].toInt() and 0xFF
        if (proto != UDP_PROTO) return // Drop non-UDP

        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != DNS_PORT) return // Drop non-DNS

        val udpPayloadOffset = ihl + 8
        val udpPayloadLen = len - udpPayloadOffset
        if (udpPayloadLen < 12) return // Malformed DNS

        val dnsPayload = packet.copyOfRange(udpPayloadOffset, udpPayloadOffset + udpPayloadLen)
        handleDnsQuery(packet, ihl, dnsPayload)
    }

    private fun handleDnsQuery(
        rawPacket: ByteArray,
        ihl: Int,
        dnsPayload: ByteArray
    ) {
        val domain = extractDomainFromDns(dnsPayload) ?: return
        val domainLower = domain.trimEnd('.').lowercase()

        if (blocklist.isBlocked(domainLower)) {
            Log.d(TAG, "BLOCKED: $domainLower")
            val nxResponse = buildNxDomainResponse(dnsPayload)
            val responsePacket = wrapDnsInUdpIp(rawPacket, ihl, nxResponse)
            if (responsePacket != null) {
                sendResponse(responsePacket)
            }
        } else {
            val realResponse = forwardDnsToUpstream(dnsPayload)
            if (realResponse != null) {
                val responsePacket = wrapDnsInUdpIp(rawPacket, ihl, realResponse)
                if (responsePacket != null) {
                    sendResponse(responsePacket)
                }
            }
        }
    }

    private fun sendResponse(responsePacket: ByteArray) {
        val result = outgoingChannel.trySend(responsePacket)
        if (result.isFailure) {
            try {
                tunOut.write(responsePacket)
            } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DNS Wire Format — Domain Extraction (RFC 1035 §3.1)
    // ─────────────────────────────────────────────────────────────

    private fun extractDomainFromDns(dns: ByteArray): String? {
        return try {
            val sb = StringBuilder()
            var i = 12 // Skip 12-byte DNS header
            var labelCount = 0

            while (i < dns.size) {
                val labelLen = dns[i].toInt() and 0xFF
                if (labelLen == 0) break
                // Guard against compression pointers (0xC0) in query questions
                if (labelLen and 0xC0 == 0xC0) return null
                if (labelLen > 63) return null
                if (++labelCount > 128) return null
                if (i + 1 + labelLen > dns.size) return null

                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dns, i + 1, labelLen, Charsets.US_ASCII))
                i += 1 + labelLen
            }
            sb.toString().ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Domain extraction failed: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DNS Response Building (RFC 1035 + RFC 6891 EDNS0 Handling)
    // ─────────────────────────────────────────────────────────────

    private fun buildNxDomainResponse(query: ByteArray): ByteArray {
        // Find end of Question section to truncate OPT/EDNS0 additional records
        var i = 12
        while (i < query.size && (query[i].toInt() and 0xFF) != 0) {
            val labelLen = query[i].toInt() and 0xFF
            if (labelLen and 0xC0 == 0xC0) {
                i += 2
                break
            }
            i += 1 + labelLen
        }
        val questionEnd = (i + 1 + 4).coerceAtMost(query.size)

        // Truncate at end of Question section (strips OPT/EDNS0 records)
        val response = query.copyOf(questionEnd)
        val queryFlags = ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF)
        val rdBit = queryFlags and 0x0100
        val responseFlags = 0x8183 or rdBit // QR=1, AA=1, RA=1, RCODE=3 (NXDOMAIN)

        response[2] = (responseFlags ushr 8).toByte()
        response[3] = (responseFlags and 0xFF).toByte()
        response[6] = 0; response[7] = 0   // ANCOUNT = 0
        response[8] = 0; response[9] = 0   // NSCOUNT = 0
        response[10] = 0; response[11] = 0 // ARCOUNT = 0

        return response
    }

    // ─────────────────────────────────────────────────────────────
    // Upstream DNS Forwarding with Multi-Server Fallback
    // ─────────────────────────────────────────────────────────────

    private fun forwardDnsToUpstream(dnsPayload: ByteArray): ByteArray? {
        for (serverIp in UPSTREAM_SERVERS) {
            var socket: DatagramSocket? = null
            try {
                val serverAddr = InetAddress.getByName(serverIp)
                socket = DatagramSocket().apply {
                    soTimeout = DNS_TIMEOUT_MS
                }

                if (vpnService != null && !vpnService.protect(socket)) {
                    Log.w(TAG, "Cannot protect socket for $serverIp")
                }

                val request = DatagramPacket(dnsPayload, dnsPayload.size, serverAddr, DNS_PORT)
                socket.send(request)

                val responseBuffer = ByteArray(4096)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)

                if (response.length > 0) {
                    return responseBuffer.copyOf(response.length)
                }
            } catch (e: Exception) {
                // Timeout or network drop on this upstream — try next immediately
                Log.d(TAG, "Upstream $serverIp failed (${e.message}), trying next...")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
            }
        }
        Log.w(TAG, "All upstream DNS servers timed out for query")
        return null
    }

    // ─────────────────────────────────────────────────────────────
    // IP/UDP Packet Assembly
    // ─────────────────────────────────────────────────────────────

    private fun wrapDnsInUdpIp(
        originalPacket: ByteArray,
        ihl: Int,
        dnsResponse: ByteArray
    ): ByteArray? {
        return try {
            val udpLen = 8 + dnsResponse.size
            val totalLen = ihl + udpLen
            val out = ByteArray(totalLen)

            // ── IPv4 Header ──
            System.arraycopy(originalPacket, 0, out, 0, ihl)

            // Direct byte swap: new src = original dst, new dst = original src
            System.arraycopy(originalPacket, 16, out, 12, 4)
            System.arraycopy(originalPacket, 12, out, 16, 4)

            // Total length
            out[2] = (totalLen ushr 8).toByte()
            out[3] = (totalLen and 0xFF).toByte()

            // Reset TTL for synthesized responses
            out[8] = 64

            // Recalculate IPv4 Checksum
            out[10] = 0
            out[11] = 0
            val checksum = ipv4Checksum(out, ihl)
            out[10] = (checksum ushr 8).toByte()
            out[11] = (checksum and 0xFF).toByte()

            // ── UDP Header ──
            val srcPort = ((originalPacket[ihl].toInt() and 0xFF) shl 8) or
                    (originalPacket[ihl + 1].toInt() and 0xFF)
            val dstPort = ((originalPacket[ihl + 2].toInt() and 0xFF) shl 8) or
                    (originalPacket[ihl + 3].toInt() and 0xFF)

            out[ihl] = (dstPort ushr 8).toByte()     // new src port = original dst (53)
            out[ihl + 1] = (dstPort and 0xFF).toByte()
            out[ihl + 2] = (srcPort ushr 8).toByte() // new dst port = original src
            out[ihl + 3] = (srcPort and 0xFF).toByte()
            out[ihl + 4] = (udpLen ushr 8).toByte()
            out[ihl + 5] = (udpLen and 0xFF).toByte()
            out[ihl + 6] = 0  // UDP checksum optional in IPv4
            out[ihl + 7] = 0

            // ── DNS Payload ──
            System.arraycopy(dnsResponse, 0, out, ihl + 8, dnsResponse.size)

            out
        } catch (e: Exception) {
            Log.e(TAG, "Packet assembly error: ${e.message}")
            null
        }
    }

    private fun ipv4Checksum(header: ByteArray, ihl: Int): Int {
        var sum = 0
        var i = 0
        while (i + 1 < ihl) {
            sum += ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < ihl) {
            sum += (header[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
