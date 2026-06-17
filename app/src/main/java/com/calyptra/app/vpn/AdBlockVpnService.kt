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
import java.util.concurrent.Semaphore
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

    /** Ceiling on concurrent in-flight DNS handlers. A DNS flood would otherwise
     *  launch an unbounded number of coroutines (one per :53 packet) and OOM the
     *  process. Packets that can't acquire a permit are dropped (the stub resolver
     *  retries). Permits are released in handleDnsRequest's finally, exactly once
     *  per successful acquire. */
    private val inFlightDnsGate = Semaphore(MAX_INFLIGHT_DNS)

    private val blocklistManager by lazy { (applicationContext as CalyptraApp).blocklistManager }
    private val categoryBlockManager by lazy { (applicationContext as CalyptraApp).categoryBlockManager }
    private val statsRepository by lazy { (applicationContext as CalyptraApp).statsRepository }
    private val safeSearchManager by lazy { (applicationContext as CalyptraApp).safeSearchManager }

    /** Parent domain allowlist (the false-positive escape hatch). Like the
     *  category matcher, this is rebuilt on change with no VPN restart — the set
     *  is tiny and an allowlisted domain short-circuits to Allow. */
    @Volatile private var allowedDomainMatcher: com.calyptra.app.blocklist.DomainMatcher? = null

    private val dnsInterceptor by lazy {
        DnsInterceptor { domain, queryType ->
            DnsPolicy.resolve(
                domain = domain,
                queryType = queryType,
                isDomainAllowed = { d -> allowedDomainMatcher?.isBlocked(d) ?: false },
                getRedirectIp = { d, t -> safeSearchManager.getRedirectIp(d, t) },
                isThreatBlocked = { d -> blocklistManager.isThreatBlocked(d) },
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
        // Tear down any collectors/jobs from a previous start before re-creating
        // them. The live-config collectors (blockedCategories, allowedDomains,
        // etc.) are launched directly on `scope` as siblings of `job`, so a plain
        // stop→start or a START_STICKY null-intent restart would otherwise leak a
        // SECOND set of collectors onto the same scope. Two blockedCategories
        // collectors racing on setEnabledCategories is how a disabled category
        // (e.g. Reddit) could stay live: an older {reddit,nsfw} rebuild outrunning
        // the newer {nsfw} one. Recreating the scope guarantees exactly one.
        scope?.cancel()
        val freshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = freshScope
        stopping = false
        job = freshScope.launch {
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

                // Bind collectors to the scope captured at start, not the mutable
                // `scope` field, so a concurrent restart can't attach them to a
                // different generation.
                freshScope.launch {
                    app.preferencesRepository.gameAdsAllowed.collect { allowed ->
                        blocklistManager.allowGameAds = allowed
                    }
                }
                freshScope.launch {
                    app.preferencesRepository.safeSearchEnabled.collect { enabled ->
                        safeSearchManager.safeSearchEnabled = enabled
                    }
                }
                freshScope.launch {
                    app.preferencesRepository.youtubeRestrictLevel.collect { level ->
                        safeSearchManager.youtubeRestrictLevel = level
                    }
                }
                freshScope.launch {
                    app.preferencesRepository.blockedCategories.collect { keys ->
                        categoryBlockManager.setEnabledCategories(
                            com.calyptra.app.blocklist.SocialCategory.fromKeys(keys)
                        )
                    }
                }
                freshScope.launch {
                    app.preferencesRepository.allowedDomains.collect { domains ->
                        // Rebuild the immutable matcher in place — no VPN restart;
                        // an empty allowlist means "match nothing".
                        allowedDomainMatcher = if (domains.isEmpty()) {
                            null
                        } else {
                            com.calyptra.app.blocklist.DomainMatcher(domains)
                        }
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

        while (isActive) {
            val length = try {
                withContext(Dispatchers.IO) {
                    inputStream.read(buffer.array())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The tunnel fd was closed (reconfigure/stop) or the read otherwise
                // failed. End the loop cleanly; letting it propagate would fail the
                // coroutine and reach the scope's (handler-less) uncaught path.
                Log.d(TAG, "Packet read ended", e)
                break
            }
            if (length <= 0) {
                delay(1)
                buffer.clear()
                continue
            }
            // Per-packet body is isolated: a single malformed packet that throws
            // (e.g. an unexpected index) must skip and keep the loop alive, never
            // terminate `while (isActive)` and silently stop servicing DNS.
            // CancellationException is rethrown so coroutine cancellation
            // (reconfigure/stop cancelling packetJob) still ends the loop.
            try {
                // A well-formed IPv4 header is at least 20 bytes; without that we
                // can't even read the protocol/header-length fields safely.
                if (length < 20) {
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
                    // headerLength can be 20..60 per IHL; reject anything that
                    // doesn't leave room for the UDP dst-port bytes we read below.
                    if (headerLength !in 20..(length - 4)) {
                        buffer.clear()
                        continue
                    }
                    val dstPort = ((packetBuffer.get(headerLength + 2).toInt() and 0xFF) shl 8) or (packetBuffer.get(headerLength + 3).toInt() and 0xFF)

                    if (dstPort == 53) {
                        // Bounded gate: drop the packet rather than launch when too
                        // many handlers are already in flight (DNS-flood OOM guard).
                        if (inFlightDnsGate.tryAcquire()) {
                            launch {
                                try {
                                    handleDnsRequest(packetBuffer, headerLength, outputStream)
                                } finally {
                                    inFlightDnsGate.release()
                                }
                            }
                        } else {
                            Log.d(TAG, "Dropping DNS query: too many in flight")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed packet", e)
            }
            buffer.clear()
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
                writeResponse(outputStream, IpUdpPacketBuilder.constructIpUdpResponse(requestPacket, response, ipHeaderLength))
                statsRepository.incrementCount()
                return
            }

            // Allowed. Forward to upstream with timeout.
            val dnsData = ByteArray(dnsPacket.remaining())
            dnsPacket.get(dnsData)

            val responseData = try {
                queryUpstream(dnsData)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "DNS Resolution failed", e)
                null
            }

            // Never leave a query hanging. A black-holed lookup forces a client-side
            // timeout and can flip the OS to "no internet" — which breaks Android
            // Auto. Answer SERVFAIL so the stub resolver fails fast and can retry.
            val payload = responseData ?: buildServfail(dnsData)
            writeResponse(outputStream, IpUdpPacketBuilder.constructIpUdpResponse(requestPacket, ByteBuffer.wrap(payload), ipHeaderLength))
        } catch (e: CancellationException) {
            // Reconfigure/stop cancelled this handler mid-flight: propagate so
            // structured cancellation tears it down promptly, matching the
            // rethrow style in processPackets — don't do redundant teardown I/O.
            throw e
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

            // Connect so the kernel only delivers datagrams from this resolver: an
            // off-path attacker can no longer race a forged reply from a different
            // source address/port onto our ephemeral socket.
            socket.connect(InetAddress.getByName(server), 53)
            socket.send(DatagramPacket(dnsData, dnsData.size))

            val responseBuffer = ByteArray(4096)
            val inPacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(inPacket)

            val response = inPacket.data.copyOf(inPacket.length)
            // Defense against off-path/blind UDP spoofing: only forward an upstream
            // answer that matches the query we sent — transaction ID, QR bit, and
            // the full question section echoed back. Anything else is dropped, not
            // relayed to the client.
            if (!DnsResponseValidator.matches(dnsData, response)) {
                Log.w(TAG, "Dropping upstream DNS response: does not match query ($server)")
                null
            } else {
                response
            }
        } catch (e: Exception) {
            null
        } finally {
            socket?.close()
        }
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
            // Cancel the whole scope so the live-config collectors (siblings of
            // `job`, not children) stop too — otherwise they leak past stop and a
            // later start would race a second set against them.
            scope?.cancel()
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

        /** Max concurrent in-flight DNS handlers; excess :53 packets are dropped. */
        private const val MAX_INFLIGHT_DNS = 128

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