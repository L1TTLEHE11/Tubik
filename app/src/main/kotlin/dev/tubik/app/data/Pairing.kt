package dev.tubik.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume

/** Сторона ТВ: сервер + объявление в сети через mDNS. */
class PairServer(private val context: Context, onCookies: (String) -> Unit) {
    private val core = PairServerCore("${Build.MANUFACTURER} ${Build.MODEL}".trim(), onCookies)
    private var nsdListener: NsdManager.RegistrationListener? = null

    val code get() = core.code
    val port get() = core.port

    fun start() {
        core.onFinished = { unregister() }
        val port = core.start()
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "Tubik ${Build.MODEL}".take(60)
            serviceType = PAIR_SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        nsdListener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun unregister() {
        nsdListener?.let { l ->
            runCatching { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(l) }
        }
        nsdListener = null
    }

    fun stop() {
        core.stop()
        unregister()
    }

    companion object {
        /** Адреса ТВ в локалке — на случай, если mDNS в роутере зарезан. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress!! }
        }.getOrDefault(emptyList())
    }
}

/** Сторона телефона: находит ТВ через mDNS (или по адресу) и отправляет вход. */
object PairClient {
    sealed class Result {
        data class Ok(val device: String) : Result()
        data class Fail(val message: String) : Result()
    }

    private const val NOT_FOUND = "ТВ не нашёлся в сети. На ТВ открыт экран с кодом? Телефон в той же Wi-Fi?"

    suspend fun send(context: Context, code: String, cookies: String, manualAddress: String?): Result {
        val norm = PairCrypto.normalize(code)
        if (norm.length != 8) return Result.Fail("В коде 8 символов, а тут ${norm.length}")
        var lastError = NOT_FOUND

        suspend fun attempt(host: InetAddress, port: Int): Result? = withContext(Dispatchers.IO) {
            when (val a = PairClientCore.tryTarget(host, port, norm, cookies)) {
                is PairClientCore.Attempt.Ok -> Result.Ok(a.device)
                PairClientCore.Attempt.BadCode -> { lastError = "Код не подошёл. Сверь буквы и цифры на экране ТВ."; null }
                PairClientCore.Attempt.Closed -> { lastError = "На ТВ окно входа уже закрыто (или было много неверных попыток). Открой его заново."; null }
                else -> null
            }
        }

        if (!manualAddress.isNullOrBlank()) {
            val host = manualAddress.substringBefore(':').trim()
            val port = manualAddress.substringAfter(':', PAIR_PORT.toString()).trim().toIntOrNull() ?: PAIR_PORT
            val addr = withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(host) }.getOrNull() }
                ?: return Result.Fail("Не понимаю адрес «$manualAddress»")
            return attempt(addr, port) ?: Result.Fail(if (lastError == NOT_FOUND) "По адресу $manualAddress Тюбик не отвечает." else lastError)
        }

        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { queue.close() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) { queue.trySend(s) }
            override fun onServiceLost(s: NsdServiceInfo) {}
        }
        runCatching { nsd.discoverServices(PAIR_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { return Result.Fail(NOT_FOUND) }
        try {
            val result = withTimeoutOrNull(12_000) {
                val tried = HashSet<String>()
                for (s in queue) {
                    // NsdManager на старых Android резолвит только по одному сервису за раз — идём по очереди
                    val (host, port) = resolve(nsd, s) ?: continue
                    if (!tried.add("${host.hostAddress}:$port")) continue
                    attempt(host, port)?.let { return@withTimeoutOrNull it }
                }
                null
            }
            return result ?: Result.Fail(lastError)
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
            queue.close()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, s: NsdServiceInfo): Pair<InetAddress, Int>? {
        repeat(4) {
            val (target, busy) = suspendCancellableCoroutine<Pair<Pair<InetAddress, Int>?, Boolean>> { cont ->
                runCatching {
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, e: Int) {
                            if (cont.isActive) cont.resume(null to (e == NsdManager.FAILURE_ALREADY_ACTIVE))
                        }
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val host = si.host
                            if (cont.isActive) cont.resume((if (host != null) host to si.port else null) to false)
                        }
                    })
                }.onFailure { if (cont.isActive) cont.resume(null to true) }
            }
            if (target != null) return target
            if (!busy) return null
            delay(400)
        }
        return null
    }
}
