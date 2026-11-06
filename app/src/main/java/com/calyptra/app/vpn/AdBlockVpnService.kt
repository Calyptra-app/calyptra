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
import com.calyptra.app.vpn.dot.DotResolver
import com.calyptra.app.worker.VpnWatchdogScheduler
import com.calyptra.app.worker.VpnWatchdogWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Semaphore
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
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
    /** Set once packet-loop retries are exhausted; this instance then refuses
     *  automatic starts until it is destroyed. */
    @Volatile private var gaveUp = false
    /** Most recent onStartCommand startId, for stopSelfResult on give-up. */
    private var lastStartId = 0
    /** Bumped per startVpn() under tunnelLock. Identifies the live generation:
     *  only it may install a tunnel or clear the starting flag. */
    @Volatile private var startGeneration = 0

    /** Ceiling on concurrent in-flight DNS handlers. A DNS flood would otherwise
     *  launch an unbounded number of coroutines (one per :53 packet) and OOM the
     *  process. Packets that can't acquire a permit are dropped (the stub resolver
     *  retries). Permits are released in handleDnsRequest's finally, exactly once
     *  per successful acquire. */
    private val inFlightDnsGate = Semaphore(MAX_INFLIGHT_DNS)

    /** Encrypted upstream transport (DNS-over-TLS). Recreated per protection
     *  session in startVpn and closed in stopVpn so pooled TLS sockets don't leak
     *  across a stop/start. Allowed queries try DoT first and fail open to UDP. */
    @Volatile private var dotResolver: DotResolver? = null
    /** Malware-only DoT transport for parent-allowlisted domains. Same
     *  per-session lifecycle as [dotResolver]. */
    @Volatile private var allowlistDotResolver: DotResolver? = null

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
        // Every delivered start (including STOP/RECONFIGURE) advances the
        // system's latest startId; give-up's stopSelfResult must compare
        // against the true latest or it silently fails to stop.
        synchronized(tunnelLock) { lastStartId = startId }
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

        val userInitiated = intent?.getBooleanExtra(EXTRA_USER_INITIATED, false) == true
        // One locked decision, shared with packet-loop recovery/give-up, so no
        // start can interleave with them (no competing generations, and no
        // start launched just before a give-up's stop).
        val accepted = synchronized(tunnelLock) {
            if (userInitiated) {
                // An explicit tap always restores protection, even after give-up.
                gaveUp = false
                packetLoopGaveUpUntil = 0L
            }
            when {
                // A start already in flight (blocklist load, SafeSearch
                // resolution, establish) reports not-running until installed,
                // so the watchdog may ask again meanwhile. Don't restart it.
                vpnInterface != null || job?.isActive == true -> true
                // Packet-loop retries exhausted: automatic starts (watchdog,
                // sticky restart, queued checks) wait out the cooldown.
                gaveUp || System.currentTimeMillis() < packetLoopGaveUpUntil -> false
                else -> {
                    startVpn()
                    true
                }
            }
        }
        if (!accepted) {
            Log.w(TAG, "Automatic start suppressed: packet-loop give-up cooldown")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
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
        // Fresh DoT transport for this session; close any pooled sockets from a
        // previous start. protect() routes its TCP sockets around our own tunnel.
        dotResolver?.close()
        dotResolver = DotResolver(DOT_ENDPOINTS, protect = { socket: Socket -> protect(socket) })
        allowlistDotResolver?.close()
        allowlistDotResolver = DotResolver(ALLOWLIST_DOT_ENDPOINTS, protect = { socket: Socket -> protect(socket) })
        val generation = ++startGeneration
        VpnController.markStarting(true)
        job = freshScope.launch {
            try {
                val app = applicationContext as CalyptraApp
                val isEnabled = app.preferencesRepository.protectionEnabled.first()
                if (!isEnabled) {
                    VpnController.updateState(false)
                    stopSelf()
                    return@launch
                }

                // Not reported as running yet: nothing is filtered until the
                // tunnel is installed below.
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

                val build = buildTunnel(app)
                when (establishAndInstall(build.builder, generation)) {
                    InstallResult.EstablishFailed -> handleStartFailure(null)
                    // Superseded or stopping: a newer generation or the
                    // teardown owns the tunnel now; nothing more to do.
                    InstallResult.Superseded -> Unit
                    // Running was published inside the critical section.
                    InstallResult.Installed -> {
                        restartGuard.reset()
                        // The whitelist was snapshotted before Running was
                        // published; a toggle in that window sent no RECONFIGURE
                        // (WhitelistViewModel only sends it while running).
                        // Reconcile once now; later toggles see Running.
                        val current = app.database.whitelistDao().getAllPackageNames().toSet()
                        if (current != build.whitelist) reconfigure()
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            VpnController.updateAlwaysOn(isAlwaysOn)
                        }
                        // Re-check protection state on connectivity transitions (PWR-L4).
                        app.networkMonitor.startWatching {
                            VpnWatchdogScheduler.checkNow(applicationContext)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Superseded by a newer start or a stop: not a startup failure.
                // Treating it as one would stopSelf() the generation replacing us.
                throw e
            } catch (e: Exception) {
                handleStartFailure(e)
            } finally {
                // Compare-and-clear under the lock: startVpn bumps the generation
                // under it too, so a newer start's flag can't be cleared here.
                synchronized(tunnelLock) {
                    if (startGeneration == generation) VpnController.markStarting(false)
                }
            }
        }
    }

    private enum class InstallResult { Installed, Superseded, EstablishFailed }

    /** establish()es [builder] and swaps the result in as the live interface,
     *  starting its packet loop and cancelling/closing any previous one, all in
     *  one tunnelLock section. establish() itself replaces the system's active
     *  VPN interface, so the generation/stopping check must come BEFORE it:
     *  a superseded start or reconfigure (or one racing a stop) never calls
     *  establish() at all. startVpn() bumps the generation under the same lock.
     *  Safe for both the initial start (no previous) and reconfigure. */
    private fun establishAndInstall(builder: Builder, generation: Int): InstallResult = synchronized(tunnelLock) {
        if (stopping || generation != startGeneration) return InstallResult.Superseded
        val tun = builder.establish() ?: return InstallResult.EstablishFailed
        val oldInterface = vpnInterface
        val oldLoop = packetJob
        // Bring the new tunnel up before tearing the old one down (no gap).
        vpnInterface = tun
        startPacketLoop(tun)
        // Published under the lock so a concurrent stop/revoke/loop failure
        // (which also clears state under it) can't be overwritten afterwards.
        VpnController.updateState(true)
        // Cancel (don't join) then close, so the old loop's blocking read is
        // unblocked by the close rather than deadlocking a join (PWR-L1).
        oldLoop?.cancel()
        oldInterface?.close()
        InstallResult.Installed
    }

    /** Builds the tun interface from the current config. Two sets of apps are
     *  excluded from the DNS route via addDisallowedApplication:
     *   - SYSTEM_EXCLUDED_PACKAGES: system/automotive apps whose connectivity
     *     checks (notably Android Auto) must never see filtered/slow DNS.
     *   - parent-whitelisted apps (WL-L1).
     *  These rules bake in at establish() time, so changing the whitelist needs
     *  a fresh interface — see reconfigure(). */
    private class TunnelBuild(val builder: Builder, val whitelist: Set<String>)

    private suspend fun buildTunnel(app: CalyptraApp): TunnelBuild {
        val builder = Builder()
            .setSession("Calyptra")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("10.0.0.1", 32)
            // Non-blocking so TunReader can drain with read() and sleep in
            // poll(); never a blocking read (it can't be woken on stop).
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

        // Not established here: establishAndInstall does that under the lock.
        return TunnelBuild(builder, whitelistedPackages.toSet())
    }

    private fun startPacketLoop(tun: ParcelFileDescriptor) {
        packetJob = scope?.launch {
            processPackets(tun)
            // A planned exit (reconfigure swap, stop) cancels this job before
            // closing the fd, so the read error that ends the loop is expected.
            // Still active here means the live tunnel failed on its own.
            if (isActive) onPacketLoopFailed(tun)
        }
    }

    /** The packet loop of [tun] ended without being cancelled: DNS is no longer
     *  serviced, so protection must not keep reporting Running (the watchdog
     *  would trust it and never recover). Rebuild in place, bounded by its own
     *  crash-loop guard; once exhausted, fall back to the start-failure path
     *  (stop + alert the parent). */
    private fun onPacketLoopFailed(tun: ParcelFileDescriptor) {
        // One critical section for teardown and the restart decision, so a stop
        // or a watchdog start (onStartCommand also decides under tunnelLock)
        // can't slip in between and race a second generation.
        // Snapshot of the latest startId at give-up time, taken in the same
        // critical section, so a start delivered afterwards (which may already
        // be recovering) is never the one we stop.
        var giveUpStartId = 0
        val retried = synchronized(tunnelLock) {
            if (stopping || vpnInterface !== tun) return
            vpnInterface = null
            tun.close()
            VpnController.updateState(false)
            Log.e(TAG, "Packet loop ended unexpectedly; tunnel torn down")
            packetLoopGuard.shouldRetryAfterFailure().also { retry ->
                if (retry) {
                    startVpn()
                } else {
                    // Terminal, set under the lock so no start slips in before
                    // the stopSelf() below and gets killed by it.
                    gaveUp = true
                    packetLoopGaveUpUntil = System.currentTimeMillis() + GIVE_UP_COOLDOWN_MS
                    giveUpStartId = lastStartId
                }
            }
        }
        if (!retried) {
            // Terminal: not handleStartFailure, whose restartGuard is reset by
            // every successful establish and would keep scheduling restarts.
            // The periodic watchdog (15 min) is the cooldown before a new try.
            Log.e(TAG, "Packet loop keeps failing; giving up and alerting")
            alertRestoreFailed()
            // Only if no start was delivered since: a user start racing this
            // give-up must survive it (onStartCommand records lastStartId).
            stopSelfResult(giveUpStartId)
        }
    }

    /** Rebuilds the live tunnel in place after a per-app routing change, without
     *  tearing the service down (no protection gap, no notification churn).
     *  Serialized via reconfigureMutex; the swap itself is done by establishAndInstall. */
    private fun reconfigure() {
        val generation = synchronized(tunnelLock) { startGeneration }
        reconfigureJob = scope?.launch {
            reconfigureMutex.withLock {
                // No-op if the tunnel isn't up yet (startVpn still establishing —
                // it will read the latest whitelist) or is already gone. Must not
                // stopSelf here, or a toggle during startup would abort the start.
                if (vpnInterface == null || stopping) return@withLock
                try {
                    val app = applicationContext as CalyptraApp
                    val result = establishAndInstall(buildTunnel(app).builder, generation)
                    if (result == InstallResult.Installed) {
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
            alertRestoreFailed()
        }
        stopSelf()
    }

    private fun alertRestoreFailed() {
        postAlertNotification(
            getString(R.string.watchdog_alert_title),
            getString(R.string.watchdog_alert_text),
            VpnWatchdogWorker.WATCHDOG_NOTIFICATION_ID
        )
    }

    // coroutineScope ties the per-query handlers below to this loop's job, so
    // cancelling packetJob also cancels in-flight handlers before the old
    // descriptor is closed during reconfigure/stop.
    private suspend fun processPackets(vpnInterface: ParcelFileDescriptor) = coroutineScope {
        val outputStream = FileOutputStream(vpnInterface.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)
        val reader = TunReader(vpnInterface.fileDescriptor)
        // Cancellation can't interrupt the reader's blocking poll(); this hook
        // wakes it when the loop is cancelled (reconfigure/stop cancel packetJob
        // before closing the fd). Registered before the first blocking read.
        val waker = onCancellation { reader.wake() }
        try {
            readLoop(reader, buffer, outputStream)
        } finally {
            waker.cancel()
            reader.close()
        }
    }

    private fun CoroutineScope.readLoop(
        reader: TunReader,
        buffer: ByteBuffer,
        outputStream: FileOutputStream
    ) {
        while (isActive) {
            // Blocks in poll() at zero CPU until a packet arrives (#72). WOKEN
            // means a planned exit; ENDED means the tunnel hung up or failed.
            // Either way the loop ends; startPacketLoop tells them apart.
            val length = reader.read(buffer.array())
            if (length < 0) {
                if (length == TunReader.ENDED) Log.d(TAG, "Packet read ended")
                break
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

            val result = dnsInterceptor.intercept(dnsPacket)
            val response = result.response

            if (response != null) {
                Log.d(TAG, "Answered DNS query locally (blocked=${result.countsAsBlocked})")
                // In-memory, non-suspending; flushed to the DB off the hot path.
                if (result.countsAsBlocked) statsRepository.recordBlocked()
                writeResponse(outputStream, IpUdpPacketBuilder.constructIpUdpResponse(requestPacket, response, ipHeaderLength))
                return
            }

            // Allowed. Forward to upstream with timeout.
            val dnsData = ByteArray(dnsPacket.remaining())
            dnsPacket.get(dnsData)

            val responseData = try {
                queryUpstream(dnsData, unfiltered = result.unfiltered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "DNS Resolution failed", e)
                null
            }

            // Never leave a query hanging. A black-holed lookup forces a client-side
            // timeout and can flip the OS to "no internet" — which breaks Android
            // Auto. Answer SERVFAIL so the stub resolver fails fast and can retry.
            // Negative answers (e.g. a family-filter block) are cached for at
            // most a minute, so allowlist changes reach every app quickly.
            val payload = responseData?.let(NegativeTtlClamp::clamp) ?: buildServfail(dnsData)
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

    private suspend fun queryUpstream(dnsData: ByteArray, unfiltered: Boolean): ByteArray? = withContext(Dispatchers.IO) {
        // Parent-allowlisted: the malware-only resolver, so the allowlist can
        // override a family-filter block upstream. If it is unreachable, fall
        // through to the filtered chain: worst case the site stays blocked,
        // never less safe.
        if (unfiltered) {
            val answer = allowlistDotResolver?.query(dnsData)
                ?: queryDnsServer(dnsData, ALLOWLIST_DNS)
                ?: queryDnsServer(dnsData, ALLOWLIST_DNS_SECONDARY)
            if (answer != null) return@withContext answer
        }
        // Encrypted DoT first (CleanBrowsing Family — filtering is preserved by the
        // named TLS profile). Fail OPEN to plain UDP on the same filtering resolvers
        // so a DoT outage/restrictive network never black-holes the child's DNS;
        // only encryption is lost while DoT is down. Both transports validate the
        // answer against the query (DnsResponseValidator).
        dotResolver?.query(dnsData)
            ?: queryDnsServer(dnsData, PRIMARY_DNS)
            // Same family filter on its second anycast address before leaving
            // CleanBrowsing: Cloudflare Families blocks less (e.g. cinobo.com),
            // so it is only the last resort when CleanBrowsing is fully down.
            ?: queryDnsServer(dnsData, SECONDARY_DNS)
            ?: queryDnsServer(dnsData, FALLBACK_DNS)
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
        synchronized(tunnelLock) {
            // Set stopping with Revoked so an install racing this can't publish
            // Running over it (establishAndInstall checks stopping under the lock).
            stopping = true
            VpnController.notifyRevoked()
        }
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
            // Set first so an in-flight reconfigure (establishAndInstall) bails out and
            // can't reinstall a tunnel after we've torn down.
            stopping = true
            VpnController.updateState(false)
            VpnController.markStarting(false)
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
            // Close pooled DoT TLS sockets so they don't leak past teardown.
            dotResolver?.close()
            allowlistDotResolver?.close()
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
        // Separate from restartGuard, which a successful establish() resets: a
        // tunnel that installs fine but whose reads keep failing must still
        // trip the breaker instead of rebuilding forever.
        private val packetLoopGuard = RestartGuard()
        /** Process-wide so a fresh service instance honors the cooldown too. */
        @Volatile private var packetLoopGaveUpUntil = 0L
        private const val GIVE_UP_COOLDOWN_MS = 15 * 60_000L
        const val EXTRA_USER_INITIATED = "com.calyptra.app.vpn.USER_INITIATED"
        private const val TAG = "AdBlockVpnService"
        private const val PRIMARY_DNS = "185.228.168.168"  // CleanBrowsing Family
        private const val SECONDARY_DNS = "185.228.169.168" // CleanBrowsing Family (2nd)
        private const val FALLBACK_DNS = "1.1.1.3"         // Cloudflare Families

        /** Parent-allowlisted domains only: Cloudflare's malware-blocking
         *  resolver (no content filtering), so the allowlist can override a
         *  CleanBrowsing block while known malware/phishing stays refused. */
        private const val ALLOWLIST_DNS = "1.1.1.2"
        private const val ALLOWLIST_DNS_SECONDARY = "1.0.0.2"
        private const val CLOUDFLARE_SECURITY_DOT_HOST = "security.cloudflare-dns.com"
        private val ALLOWLIST_DOT_ENDPOINTS = listOf(
            DotResolver.DotEndpoint(ALLOWLIST_DNS, CLOUDFLARE_SECURITY_DOT_HOST),
            DotResolver.DotEndpoint(ALLOWLIST_DNS_SECONDARY, CLOUDFLARE_SECURITY_DOT_HOST),
        )

        /** DoT endpoints (port 853). CleanBrowsing's Family profile is selected by
         *  the TLS hostname, so adult filtering is guaranteed over the encrypted
         *  link — unlike Cloudflare 1.1.1.3, whose IP-selected filtering is not
         *  reliably applied over DoT, so it stays on the UDP fallback only.
         *  Addressed by IP (cert verified against the hostname) to avoid a DNS
         *  bootstrap loop. */
        private const val CLEANBROWSING_FAMILY_DOT_HOST = "family-filter-dns.cleanbrowsing.org"
        private val DOT_ENDPOINTS = listOf(
            DotResolver.DotEndpoint("185.228.168.168", CLEANBROWSING_FAMILY_DOT_HOST),
            DotResolver.DotEndpoint("185.228.169.168", CLEANBROWSING_FAMILY_DOT_HOST),
        )
    }
}