package dev.tubik.app.data

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/*
 * Передача входа с телефона на ТВ по домашней сети — ядро без Android-зависимостей.
 *
 * ТВ: поднимает маленький HTTP-сервер, объявляет себя через mDNS (_tubik._tcp) и показывает код.
 * Телефон: находит ТВ, берёт у него соль, выводит из кода ключ (PBKDF2) и шлёт cookies,
 * зашифрованные AES-GCM. ТВ принимает, только если расшифровка прошла — то есть код верный.
 * Ни код, ни cookies по сети в открытом виде не ходят.
 */

const val PAIR_SERVICE_TYPE = "_tubik._tcp."
const val PAIR_PORT = 47110
private const val MAX_FAILS = 15
private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

object PairCrypto {
    private val rnd = SecureRandom()

    fun newCode(): String = (1..8).map { CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    fun pretty(code: String) = code.chunked(4).joinToString("-")

    fun randomBytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    fun key(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(normalize(code).toCharArray(), salt, 20_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(key: SecretKeySpec, plain: String): String {
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return (iv + c.doFinal(plain.toByteArray())).toByteString().base64()
    }

    /** null — если код неверный (тег GCM не сошёлся) или мусор. */
    fun decrypt(key: SecretKeySpec, data: String): String? = try {
        val raw = data.trim().decodeBase64()?.toByteArray() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        null
    }
}

/** Мини-HTTP поверх сокетов: так не нужно разрешать cleartext для всего приложения. */
internal object MiniHttp {
    class Req(val method: String, val path: String, val body: String)

    fun readRequest(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16_384) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val (method, path) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()?.coerceAtMost(1_000_000) ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        return Req(method, path, String(body, 0, read))
    }

    fun response(code: Int, body: String): ByteArray {
        val b = body.toByteArray()
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; 410 -> "Gone"; else -> "Error" }
        return ("HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray() + b
    }

    /** Простой запрос без OkHttp. Возвращает код и тело. */
    fun request(host: InetAddress, port: Int, method: String, path: String, body: String = ""): Pair<Int, String> {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 15_000
            val b = body.toByteArray()
            s.getOutputStream().apply {
                write(("$method $path HTTP/1.1\r\nHost: ${host.hostAddress}:$port\r\nContent-Type: text/plain\r\n" +
                    "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(b)
                flush()
            }
            val all = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = all.substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            return code to all.substringAfter("\r\n\r\n", "")
        }
    }
}

/** Сервер на ТВ: принимает зашифрованные cookies. */
class PairServerCore(
    private val deviceName: String,
    private val onCookies: (String) -> Unit,
    val code: String = PairCrypto.newCode(),
) {
    private val salt = PairCrypto.randomBytes(16)
    private val key by lazy { PairCrypto.key(code, salt) }
    private var server: ServerSocket? = null
    @Volatile private var done = false
    @Volatile private var fails = 0
    var onFinished: () -> Unit = {}

    val port get() = server?.localPort ?: 0

    fun start(preferredPort: Int = PAIR_PORT): Int {
        val ss = runCatching { ServerSocket(preferredPort) }.getOrElse { ServerSocket(0) }
        server = ss
        thread(name = "tubik-pair", isDaemon = true) {
            while (!done && !ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching { handle(client) }
                runCatching { client.close() }
            }
        }
        return ss.localPort
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val req = MiniHttp.readRequest(BufferedInputStream(client.getInputStream())) ?: return
        val resp = when {
            req.method == "GET" && req.path == "/hello" -> MiniHttp.response(200, JsonWriter.string(JsonObject().apply {
                put("app", "tubik")
                put("device", deviceName)
                put("salt", salt.toByteString().base64())
            }))
            req.method == "POST" && req.path == "/pair" -> {
                if (done || fails >= MAX_FAILS) MiniHttp.response(410, "{\"error\":\"closed\"}")
                else {
                    val plain = PairCrypto.decrypt(key, req.body)
                    val cookies = plain?.let { runCatching { JsonParser.`object`().from(it).getString("cookies") }.getOrNull() }
                    if (cookies.isNullOrBlank()) {
                        fails++
                        Thread.sleep(700) // чтобы подбирать код было совсем грустно
                        MiniHttp.response(403, "{\"error\":\"bad code\"}")
                    } else {
                        done = true
                        onCookies(cookies)
                        MiniHttp.response(200, "{\"ok\":true}")
                    }
                }
            }
            else -> MiniHttp.response(404, "{}")
        }
        client.getOutputStream().apply { write(resp); flush() }
        if (done) { stop(); onFinished() }
    }

    fun stop() {
        done = true
        runCatching { server?.close() }
    }
}

/** Клиент на телефоне: одна попытка отправить вход на конкретный адрес. */
object PairClientCore {
    sealed class Attempt {
        data class Ok(val device: String) : Attempt()
        object NotTubik : Attempt()
        object BadCode : Attempt()
        object Closed : Attempt()
        data class Error(val e: Throwable) : Attempt()
    }

    fun tryTarget(host: InetAddress, port: Int, code: String, cookies: String): Attempt = try {
        val (hc, hb) = MiniHttp.request(host, port, "GET", "/hello")
        val hello = if (hc == 200) runCatching { JsonParser.`object`().from(hb) }.getOrNull() else null
        if (hello == null || hello.getString("app") != "tubik") Attempt.NotTubik
        else {
            val salt = hello.getString("salt").decodeBase64()!!.toByteArray()
            val payload = JsonWriter.string(JsonObject().apply { put("cookies", cookies) })
            val enc = PairCrypto.encrypt(PairCrypto.key(code, salt), payload)
            when (MiniHttp.request(host, port, "POST", "/pair", enc).first) {
                200 -> Attempt.Ok(hello.getString("device") ?: "ТВ")
                403 -> Attempt.BadCode
                410 -> Attempt.Closed
                else -> Attempt.NotTubik
            }
        }
    } catch (e: Exception) {
        Attempt.Error(e)
    }
}
