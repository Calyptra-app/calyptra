package com.calyptra.app.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.calyptra.app.CalyptraApp
import com.calyptra.app.MainActivity
import com.calyptra.app.R
import com.calyptra.app.worker.VpnWatchdogScheduler
import com.calyptra.app.worker.VpnWatchdogWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class AdBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null
    private var packetJob: Job? = null
    private var reconfigureJob: Job? = null
    private var scope: CoroutineScope? = null

    /** Serializes reconfigure requests so two establish()/swap sequences can't
     *  interleave and leave a stale interface installed. */
    private val reconfigureMutex = Mutex()
    /** Guards the interface swap/teardown so the synchronous stop path and the
     *  async reconfigure path can't race on vpnInterface/packetJob. */
    private val tunnelLock = Any()
    @Volatile private var stopping = false
    
    private val blocklistManager by lazy { (applicationContext as CalyptraApp).blocklistManager }
    private val categoryBlockManager by lazy { (applicationContext as CalyptraApp).categoryBlockManager }
    private val statsRepository by lazy { (applicationContext as CalyptraApp).statsRepository }
    private val safeSearchManager by lazy { (applicationContext as CalyptraApp).safeSearchManager }
    private val dnsInterceptor by lazy {
        DnsInterceptor { domain, queryType ->
            DnsPolicy.resolve(
                domain = domain,
                queryType = queryType,
                getRedirectIp = { d, t -> safeSearchManager.getRedirectIp(d, t) },
                isCategoryBlocked = { d -> categoryBlockManager.isCategoryBlocked(d) },
                isAdBlocked = { d -> blocklistManager.isBlocked(d) }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_RECONFIGURE -> {
                // Per-app routing changed (e.g. a whitelist toggle). Rebuild the
                // live tunnel in place so it applies without cycling protection.
                reconfigure()
                return START_STICKY
            }
        }

        if (intent == null) {
            // START_STICKY restart after an OS kill — restore path (PWR-L1).
            Log.i(TAG, "Null-intent restart: restoring protection after process kill")
        }

        startForeground(NOTIFICATION_ID, createNotification())

        if (vpnInterface == null) {
            startVpn()
        }

        return START_STICKY
    }

    private fun startVpn() {
        job = scope?.launch {
            try {
                stopping = false
                val app = applicationContext as CalyptraApp
                val isEnabled = app.preferencesRepository.protectionEnabled.first()
                if (!isEnabled) {
                    VpnController.updateState(false)
                    stopSelf()
                    return@launch
                }

                VpnController.updateState(true)
                blocklistManager.initialize() // Ensure loaded
                safeSearchManager.resolveEndpoints()

                scope?.launch {
                    app.preferencesRepository.gameAdsAllowed.collect { allowed ->
                        blocklistManager.allowGameAds = allowed
                    }
                }
                scope?.launch {
                    app.preferencesRepository.safeSearchEnabled.collect { enabled ->
                        safeSearchManager.safeSearchEnabled = enabled
                    }
                }
                scope?.launch {
                    app.preferencesRepository.youtubeRestrictLevel.collect { level ->
                        safeSearchManager.youtubeRestrictLevel = level
                    }
                }
                scope?.launch {
                    app.preferencesRepository.blockedCategories.collect { keys ->
                        categoryBlockManager.setEnabledCategories(
                            com.calyptra.app.blocklist.SocialCategory.fromKeys(keys)
                        )
                    }
                }

                val tun = buildTunnel(app)
                when {
                    tun == null -> handleStartFailure(null)
                    installTunnel(tun) -> {
                        restartGuard.reset()
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            VpnController.updateAlwaysOn(isAlwaysOn)
                        }
                        // Re-check protection state on connectivity transitions (PWR-L4).
                        app.networkMonitor.startWatching {
                            VpnWatchdogScheduler.checkNow(applicationContext)
                        }
                    }
                    // installTunnel == false: service is tearing down; it already
                    // closed the new interface — nothing more to do.
                }
            } catch (e: Exception) {
                handleStartFailure(e)
            }
        }
    }

    /** Atomically swaps in [tun] as the live interface and starts its packet
     *  loop, cancelling and closing any previous one. Returns false (and closes
     *  [tun]) if the service is tearing down, so a late establish can't reinstall
     *  protection after stop. Safe for both the initial start (no previous) and
     *  reconfigure. */
    private fun installTunnel(tun: ParcelFileDescriptor): Boolean = synchronized(tunnelLock) {
        if (stopping) {
            tun.close()
            return false
        }
        val oldInterface = vpnInterface
        val oldLoop = packetJob
        // Bring the new tunnel up before tearing the old one down (no gap).
        vpnInterface = tun
        startPacketLoop(tun)
        // Cancel (don't join) then close, so the old loop's blocking read is
        // unblocked by the close rather than deadlocking a join (PWR-L1).
        oldLoop?.cancel()
        oldInterface?.close()
        true
    }

    /** Builds the tun interface from the current config. Two sets of apps are
     *  excluded from the DNS route via addDisallowedApplication:
     *   - SYSTEM_EXCLUDED_PACKAGES: system/automotive apps whose connectivity
     *     checks (notably Android Auto) must never see filtered/slow DNS.
     *   - parent-whitelisted apps (WL-L1).
     *  These rules bake in at establish() time, so changing the whitelist needs
     *  a fresh interface — see reconfigure(). */
    private suspend fun buildTunnel(app: CalyptraApp): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Calyptra")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("10.0.0.1", 32)
            .setBlocking(false)

        for (pkg in SYSTEM_EXCLUDED_PACKAGES) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                // Package not installed on this device — nothing to exclude.
            }
        }

        val whitelistedPackages = app.database.whitelistDao().getAllPackageNames()
        for (pkg in whitelistedPackages) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to whitelist $pkg", e)
            }
        }

        return builder.establish()
    }

    private fun startPacketLoop(tun: ParcelFileDescriptor) {
        packetJob = scope?.launch { processPackets(tun) }
    }

    /** Rebuilds the live tunnel in place after a per-app routing change, without
     *  tearing the service down (no protection gap, no notification churn).
     *  Serialized via reconfigureMutex; the swap itself is done by installTunnel. */
    private fun reconfigure() {
        reconfigureJob = scope?.launch {
            reconfigureMutex.withLock {
                // No-op if the tunnel isn't up yet (startVpn still establishing —
                // it will read the latest whitelist) or is already gone. Must not
                // stopSelf here, or a toggle during startup would abort the start.
                if (vpnInterface == null || stopping) return@withLock
                try {
                    val app = applicationContext as CalyptraApp
                    val newTun = buildTunnel(app) ?: return@withLock
                    if (installTunnel(newTun)) {
                        Log.i(TAG, "Tunnel reconfigured (per-app routing updated)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reconfigure failed", e)
                }
            }
        }
    }

    /** Crash-loop guard (PWR-L1): retry via an immediate watchdog check, or
     *  give up after 3 failures in 5 min and alert the parent instead. */
    private fun handleStartFailure(cause: Exception?) {
        Log.e(TAG, "Error starting VPN", cause)
        VpnController.updateState(false)
        if (restartGuard.shouldRetryAfterFailure()) {
            VpnWatchdogScheduler.checkNow(applicationContext)
        } else {
            Log.e(TAG, "VPN start failed repeatedly; giving up and alerting")
            postAlertNotification(
                getString(R.string.watchdog_alert_title),
                getString(R.string.watchdog_alert_text),
                VpnWatchdogWorker.WATCHDOG_NOTIFICATION_ID
            )
        }
        stopSelf()
    }

    // coroutineScope ties the per-query handlers below to this loop's job, so
    // cancelling packetJob also cancels in-flight handlers before the old
    // descriptor is closed during reconfigure/stop.
    private suspend fun processPackets(vpnInterface: ParcelFileDescriptor) = coroutineScope {
        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        try {
            while (isActive) {
                val length = withContext(Dispatchers.IO) {
                    inputStream.read(buffer.array())
                }
                if (length <= 0) {
                    delay(1)
                    buffer.clear()
                    continue
                }
                // Create a copy of the current packet data for async processing
                val packetData = buffer.array().copyOf(length)
                val packetBuffer = ByteBuffer.wrap(packetData)

                // Simple check for IPv4 and UDP
                val version = (packetBuffer.get(0).toInt() shr 4) and 0xF
                val protocol = packetBuffer.get(9).toInt()

                if (version == 4 && protocol == 17) {
                    val ihl = packetBuffer.get(0).toInt() and 0xF
                    val headerLength = ihl * 4
                    val dstPort = ((packetBuffer.get(headerLength + 2).toInt() and 0xFF) shl 8) or (packetBuffer.get(headerLength + 3).toInt() and 0xFF)

                    if (dstPort == 53) {
                        launch {
                            handleDnsRequest(packetBuffer, headerLength, outputStream)
                        }
                    }
                }
                buffer.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Packet loop error", e)
        }
    }

    private suspend fun handleDnsRequest(
        requestPacket: ByteBuffer, 
        ipHeaderLength: Int, 
        outputStream: FileOutputStream
    ) {
        try {
            val payloadOffset = ipHeaderLength + 8
            requestPacket.position(payloadOffset)
            val dnsPacket = requestPacket.slice()

            val response = dnsInterceptor.processDnsPacket(dnsPacket)

            if (response != null) {
                Log.d(TAG, "Blocked DNS query")
                writeResponse(outputStream, constructIpUdpResponse(requestPacket, response, ipHeaderLength))
                statsRepository.incrementCount()
                return
            }

            // Allowed. Forward to upstream with timeout.
            val dnsData = ByteArray(dnsPacket.remaining())
            dnsPacket.get(dnsData)

            val responseData = try {
                queryUpstream(dnsData)
            } catch (e: Exception) {
                Log.e(TAG, "DNS Resolution failed", e)
                null
            }

            // Never leave a query hanging. A black-holed lookup forces a client-side
            // timeout and can flip the OS to "no internet" — which breaks Android
            // Auto. Answer SERVFAIL so the stub resolver fails fast and can retry.
            val payload = responseData ?: buildServfail(dnsData)
            writeResponse(outputStream, constructIpUdpResponse(requestPacket, ByteBuffer.wrap(payload), ipHeaderLength))
        } catch (e: Exception) {
            // The tunnel may have been torn down mid-flight (reconfigure/stop),
            // closing the stream. Swallow so a stale handler can't crash the loop.
            Log.w(TAG, "Dropped DNS response (tunnel changed?)", e)
        }
    }

    private suspend fun writeResponse(outputStream: FileOutputStream, data: ByteArray) {
        withContext(Dispatchers.IO) {
            synchronized(outputStream) {
                outputStream.write(data)
            }
        }
    }

    /** Minimal SERVFAIL built from the request bytes: flip QR on and set RCODE=2.
     *  Keeps the original ID and question section so the resolver matches it to
     *  the outstanding query. */
    private fun buildServfail(requestDns: ByteArray): ByteArray {
        val r = requestDns.copyOf()
        if (r.size >= 4) {
            r[2] = (r[2].toInt() or 0x80).toByte()           // QR=1 (preserve opcode + RD)
            // RA=1, RCODE=2 (SERVFAIL); preserve the query's CD bit (RFC 4035).
            r[3] = ((r[3].toInt() and 0x10) or 0x82).toByte()
        }
        return r
    }

    private suspend fun queryUpstream(dnsData: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        queryDnsServer(dnsData, PRIMARY_DNS) ?: queryDnsServer(dnsData, FALLBACK_DNS)
    }

    private fun queryDnsServer(dnsData: ByteArray, server: String): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 2500

            val outPacket = DatagramPacket(dnsData, dnsData.size, InetAddress.getByName(server), 53)
            socket.send(outPacket)

            val responseBuffer = ByteArray(4096)
            val inPacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(inPacket)

            inPacket.data.copyOf(inPacket.length)
        } catch (e: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private fun constructIpUdpResponse(request: ByteBuffer, dnsPayload: ByteBuffer, ipHeaderLength: Int): ByteArray {
        val requestArray = request.array()
        
        val srcIpOffset = 12
        val dstIpOffset = 16
        val srcPortOffset = ipHeaderLength
        val dstPortOffset = ipHeaderLength + 2
        val udpLengthOffset = ipHeaderLength + 4
        val udpChecksumOffset = ipHeaderLength + 6
        
        val totalLength = ipHeaderLength + 8 + dnsPayload.remaining()
        val response = ByteArray(totalLength)
        
        // Copy IP Header
        System.arraycopy(requestArray, 0, response, 0, ipHeaderLength)
        
        // Fix IP Length
        response[2] = ((totalLength shr 8) and 0xFF).toByte()
        response[3] = (totalLength and 0xFF).toByte()
        
        // Swap IPs
        System.arraycopy(requestArray, srcIpOffset, response, dstIpOffset, 4)
        System.arraycopy(requestArray, dstIpOffset, response, srcIpOffset, 4)
        
        // Reset Checksum for calculation
        response[10] = 0
        response[11] = 0
        val ipChecksum = calculateChecksum(response, 0, ipHeaderLength)
        response[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        response[11] = (ipChecksum and 0xFF).toByte()
        
        // Copy UDP Header
        System.arraycopy(requestArray, ipHeaderLength, response, ipHeaderLength, 8)
        
        // Swap Ports
        System.arraycopy(requestArray, srcPortOffset, response, dstPortOffset, 2)
        System.arraycopy(requestArray, dstPortOffset, response, srcPortOffset, 2)
        
        // Fix UDP Length
        val udpLength = 8 + dnsPayload.remaining()
        response[udpLengthOffset] = ((udpLength shr 8) and 0xFF).toByte()
        response[udpLengthOffset+1] = (udpLength and 0xFF).toByte()
        
        // Copy DNS Payload
        val dnsBytes = ByteArray(dnsPayload.remaining())
        dnsPayload.get(dnsBytes)
        System.arraycopy(dnsBytes, 0, response, ipHeaderLength + 8, dnsBytes.size)
        
        // UDP Checksum calculation
        response[udpChecksumOffset] = 0
        response[udpChecksumOffset+1] = 0
        val udpChecksum = calculateUdpChecksum(response, ipHeaderLength, udpLength)
        response[udpChecksumOffset] = ((udpChecksum shr 8) and 0xFF).toByte()
        response[udpChecksumOffset+1] = (udpChecksum and 0xFF).toByte()
        
        return response
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun calculateUdpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0
        // Pseudo Header: Src IP(4), Dst IP(4), Zero(1), Proto(1), UDPLen(2)
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i+1].toInt() and 0xFF)
        }
        sum += 17 // Protocol UDP
        sum += udpLength
        
        // UDP Header + Payload
        for (i in 0 until udpLength step 2) {
            if (i + 1 >= udpLength) {
                sum += (packet[udpOffset + i].toInt() and 0xFF) shl 8
            } else {
                sum += ((packet[udpOffset + i].toInt() and 0xFF) shl 8) or (packet[udpOffset + i + 1].toInt() and 0xFF)
            }
        }
        
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        var finalSum = sum.inv() and 0xFFFF
        if (finalSum == 0) finalSum = 0xFFFF
        return finalSum
    }

    override fun onRevoke() {
        // Another app's VpnService.establish() succeeded — the OS revoked ours
        // (CFT-L1). Mark the distinct Revoked state BEFORE cleanup so it
        // survives stopVpn()'s updateState(false), then alert the parent.
        VpnController.notifyRevoked()
        val app = applicationContext as CalyptraApp
        app.protectionEventRepository
            .logAsync(com.calyptra.app.data.ProtectionEventType.REVOKED_OTHER_VPN)
        // Persist the yield so the watchdog/boot don't restart us and kill the
        // other VPN — survives process death, unlike the in-memory Revoked state.
        app.persistYieldAsync(true)
        postRevokedNotification()
        stopVpn()
    }

    private fun postRevokedNotification() {
        postAlertNotification(
            getString(R.string.revoked_alert_title),
            getString(R.string.revoked_alert_text),
            REVOKED_NOTIFICATION_ID
        )
    }

    private fun postAlertNotification(title: String, text: String, id: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CalyptraApp.ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    private fun stopVpn() {
        synchronized(tunnelLock) {
            // Set first so an in-flight reconfigure (installTunnel) bails out and
            // can't reinstall a tunnel after we've torn down.
            stopping = true
            VpnController.updateState(false)
            // Cancel loops BEFORE closing the interface so the read doesn't throw
            // into the log as a phantom failure (PWR-L1).
            reconfigureJob?.cancel()
            packetJob?.cancel()
            job?.cancel()
            vpnInterface?.close()
            vpnInterface = null
        }
        (applicationContext as CalyptraApp).networkMonitor.stopWatching()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val channelId = CalyptraApp.CHANNEL_ID
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        
        return Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.protection_enabled))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        scope?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.calyptra.app.vpn.STOP"
        const val ACTION_RECONFIGURE = "com.calyptra.app.vpn.RECONFIGURE"
        const val NOTIFICATION_ID = 1
        const val REVOKED_NOTIFICATION_ID = 2

        /** System/automotive apps that must never route through the kids' DNS
         *  filter — otherwise their connectivity checks (notably Android Auto)
         *  see broken/slow DNS and refuse to connect. Missing packages are
         *  ignored at establish() time. */
        private val SYSTEM_EXCLUDED_PACKAGES = listOf(
            "com.google.android.projection.gearhead", // Android Auto
            "com.google.android.apps.maps",           // Maps (Android Auto navigation)
            "com.google.android.gms",                 // Google Play services
        )

        // Process-wide so the 5-min failure window survives service restarts.
        private val restartGuard = RestartGuard()
        private const val TAG = "AdBlockVpnService"
        private const val PRIMARY_DNS = "185.228.168.168"  // CleanBrowsing Family
        private const val FALLBACK_DNS = "1.1.1.3"         // Cloudflare Families
    }
}