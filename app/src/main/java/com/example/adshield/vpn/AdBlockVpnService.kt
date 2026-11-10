package com.example.adshield.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.adshield.MainActivity
import com.example.adshield.R
import com.example.adshield.blocklist.BlocklistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core VPN service that creates a local TUN interface and routes all DNS traffic
 * through [DnsPacketProcessor] for ad blocking.
 *
 * Intercepts ONLY DNS traffic (10.0.0.2/32 and fd00::2/128), leaving regular internet
 * traffic (TCP/UDP) untouched to route normally over Wi-Fi / Cellular.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlockVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "adshield_vpn"
        private const val CHANNEL_NAME = "AdShield VPN"

        // Virtual DNS addresses
        private const val VPN_ADDRESS_IPV4 = "10.0.0.2"
        private const val VPN_DNS_IPV4 = "10.0.0.2"
        private const val VPN_ADDRESS_IPV6 = "fd00::2"
        private const val VPN_DNS_IPV6 = "fd00::2"

        // Actions
        const val ACTION_START = "com.example.adshield.START"
        const val ACTION_STOP  = "com.example.adshield.STOP"

        // Observable states for UI
        private val _isVpnRunning = MutableStateFlow(false)
        val isVpnRunning = _isVpnRunning.asStateFlow()

        private val _blockedDomainsCount = MutableStateFlow(0)
        val blockedDomainsCount = _blockedDomainsCount.asStateFlow()

        private val _loadedRulesCount = MutableStateFlow(0)
        val loadedRulesCount = _loadedRulesCount.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + job)
    private val stopRequested = AtomicBoolean(false)

    private var tunFd: ParcelFileDescriptor? = null
    private var processor: DnsPacketProcessor? = null
    private lateinit var blocklist: BlocklistManager

    override fun onCreate() {
        super.onCreate()
        blocklist = BlocklistManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        stopRequested.set(false)
        startServiceForeground()
        serviceScope.launch {
            startVpn()
        }
        return START_STICKY
    }

    private fun startServiceForeground() {
        val notification = buildNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopVpn()
        job.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by user/system")
        stopVpn()
        super.onRevoke()
    }

    private suspend fun startVpn() = withContext(Dispatchers.IO) {
        var inputStream: FileInputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            if (stopRequested.get()) return@withContext

            // 1. Load blocklists
            val domainCount = blocklist.load()
            _loadedRulesCount.value = domainCount
            Log.i(TAG, "Loaded $domainCount blocked domains")
            updateNotification(domainCount)

            if (stopRequested.get()) return@withContext

            // 2. Establish TUN interface (DNS routing ONLY)
            val builder = Builder()
                .setSession("AdShield")
                // IPv4 DNS sinkhole
                .addAddress(VPN_ADDRESS_IPV4, 32)
                .addDnsServer(VPN_DNS_IPV4)
                .addRoute(VPN_ADDRESS_IPV4, 32)
                // IPv6 DNS sinkhole
                .addAddress(VPN_ADDRESS_IPV6, 128)
                .addDnsServer(VPN_DNS_IPV6)
                .addRoute(VPN_ADDRESS_IPV6, 128)
                .setMtu(1500)
                .setBlocking(true)

            val fd = builder.establish()
            if (fd == null) {
                Log.e(TAG, "Failed to establish VPN — another VPN may be active")
                stopSelf()
                return@withContext
            }

            tunFd = fd
            _isVpnRunning.value = true
            Log.i(TAG, "TUN interface established successfully")

            // 3. Start DNS packet processing
            inputStream = FileInputStream(fd.fileDescriptor)
            outputStream = FileOutputStream(fd.fileDescriptor)

            val dnsProcessor = DnsPacketProcessor(
                tunIn = inputStream,
                tunOut = outputStream,
                blocklist = blocklist,
                vpnService = this@AdBlockVpnService,
            )
            processor = dnsProcessor

            // Start coroutine-based worker pool
            dnsProcessor.start(serviceScope)

            // Periodically sync blocked counter to UI & notification
            while (isActive && !stopRequested.get() && dnsProcessor.isRunning) {
                delay(1000)
                val currentBlocked = blocklist.totalBlockedSessions
                if (_blockedDomainsCount.value != currentBlocked) {
                    _blockedDomainsCount.value = currentBlocked
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "VPN error: ${e.message}", e)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
            stopVpn()
        }
    }

    private fun stopVpn() {
        stopRequested.set(true)
        _isVpnRunning.value = false

        processor?.stop()
        processor = null

        try {
            tunFd?.close()
        } catch (_: Exception) {}
        tunFd = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AdShield VPN status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(blockedDomains: Int): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AdBlockVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🛡️ AdShield Active")
            .setContentText("Blocking ads across apps ($blockedDomains rules loaded)")
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(blockedDomains: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(blockedDomains))
    }
}
