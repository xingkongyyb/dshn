package dev.dshn.app.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dev.dshn.app.data.AdbTransportMode
import dev.dshn.app.security.AdbArgvPolicy
import dev.dshn.app.security.KeystoreCrypto
import dev.dshn.app.security.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

@Serializable
data class DiscoveredAdbEndpoint(
    val serviceName: String,
    val serviceType: String,
    val host: String,
    val port: Int
)

@Serializable
data class AdbExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

data class AdbIdentitySummary(
    val md5Fingerprint: String,
    val sha256Fingerprint: String,
    val androidPublicKeyLine: String
)

/**
 * Pure Kotlin ADB client supporting both ADB over TLSv1.3 (`_adb-tls-connect._tcp.`)
 * and PC-enabled plain-TCP ADB (`adb tcpip 5555`) with standard RSA `CMD_AUTH` handshake.
 * Commands must always pass [AdbArgvPolicy.evaluate] before reaching the wire.
 */
class WirelessAdbClient(
    private val securityDir: File,
    private val cipher: SecretCipher = KeystoreCrypto,
    private val androidContext: Context? = null
) {
    constructor(context: Context) : this(
        securityDir = File(context.filesDir.absolutePath.replaceFirst("/data/user/0/", "/data/data/"), "security"),
        cipher = KeystoreCrypto,
        androidContext = context.applicationContext
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val identityFile = File(securityDir, "adb_identity.enc")

    @Serializable
    private data class StoredIdentity(val pkcs8Base64: String, val certDerBase64: String)

    private val _endpoints = MutableStateFlow<List<DiscoveredAdbEndpoint>>(emptyList())
    val endpoints: StateFlow<List<DiscoveredAdbEndpoint>> = _endpoints.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var connectListener: NsdManager.DiscoveryListener? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startDiscovery() {
        val ctx = androidContext ?: return
        if (_scanning.value) return
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        runCatching {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("dshn-adb-nsd")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        _scanning.value = true
        connectListener = createListener(nsd, SERVICE_CONNECT).also {
            nsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, it)
        }
        pairingListener = createListener(nsd, SERVICE_PAIRING).also {
            nsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, it)
        }
    }

    fun stopDiscovery() {
        val ctx = androidContext ?: return
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        connectListener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        pairingListener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        connectListener = null
        pairingListener = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        _scanning.value = false
    }

    @Suppress("DEPRECATION")
    private fun createListener(nsd: NsdManager, type: String) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String?) {}
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    val port = resolved.port
                    if (port <= 0) return
                    val entry = DiscoveredAdbEndpoint(resolved.serviceName, type, host, port)
                    val cur = _endpoints.value.filterNot { it.host == host && it.port == port }
                    _endpoints.value = cur + entry
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            _endpoints.value = _endpoints.value.filterNot { it.serviceName == serviceInfo.serviceName }
        }

        override fun onDiscoveryStopped(serviceType: String?) {}
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            _scanning.value = false
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
    }

    fun loadOrCreateIdentity(): Pair<PrivateKey, X509Certificate> {
        if (identityFile.exists()) {
            val loaded = runCatching {
                val raw = String(cipher.decrypt(identityFile.readBytes()), Charsets.UTF_8)
                val stored = json.decodeFromString<StoredIdentity>(raw)
                val keyBytes = Base64.getDecoder().decode(stored.pkcs8Base64)
                val certBytes = Base64.getDecoder().decode(stored.certDerBase64)
                val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                priv to cert
            }.getOrNull()
            if (loaded != null) return loaded
        }

        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = kpg.generateKeyPair()
        val cert = buildSelfSignedX509(pair, "CN=dshn-device-bridge")
        val stored = StoredIdentity(
            pkcs8Base64 = Base64.getEncoder().encodeToString(pair.private.encoded),
            certDerBase64 = Base64.getEncoder().encodeToString(cert.encoded)
        )
        securityDir.mkdirs()
        val enc = cipher.encrypt(json.encodeToString(stored).toByteArray(Charsets.UTF_8))
        identityFile.writeBytes(enc)
        identityFile.setReadable(false, false); identityFile.setReadable(true, true)
        identityFile.setWritable(false, false); identityFile.setWritable(true, true)
        return pair.private to cert
    }

    fun getIdentitySummary(): AdbIdentitySummary {
        val (_, cert) = loadOrCreateIdentity()
        val pub = cert.publicKey as RSAPublicKey
        val structBytes = encodeAdbRsaPublicKey(pub)
        return AdbIdentitySummary(
            md5Fingerprint = md5ColonFingerprint(structBytes),
            sha256Fingerprint = sha256Base64Fingerprint(structBytes),
            androidPublicKeyLine = formatAdbPublicKeyLine(pub)
        )
    }

    suspend fun executeAllowed(
        host: String,
        port: Int,
        allowed: AdbArgvPolicy.PolicyDecision.Allowed,
        timeoutMs: Int = 15_000,
        transportMode: AdbTransportMode = AdbTransportMode.AUTO
    ): AdbExecResult = withContext(Dispatchers.IO) {
        val (privateKey, cert) = loadOrCreateIdentity()
        val pubKey = cert.publicKey as RSAPublicKey
        val usePlainTcp = when (transportMode) {
            AdbTransportMode.PLAIN_TCP -> true
            AdbTransportMode.TLS -> false
            AdbTransportMode.AUTO -> port == 5555
        }
        val socket: Socket = if (usePlainTcp) {
            openPlainTcpSocket(host, port, timeoutMs)
        } else {
            openTlsSocket(host, port, privateKey, cert, timeoutMs)
        }
        socket.use { sock ->
            val inp = sock.inputStream
            val out = sock.outputStream
            performCnxnHandshake(
                sock = sock,
                inp = inp,
                out = out,
                privateKey = privateKey,
                publicKey = pubKey,
                baseTimeoutMs = timeoutMs,
                allowPublicKeyAuth = usePlainTcp
            )
            runShellV2Stream(inp, out, allowed)
        }
    }

    private fun performCnxnHandshake(
        sock: Socket,
        inp: InputStream,
        out: OutputStream,
        privateKey: PrivateKey,
        publicKey: RSAPublicKey,
        baseTimeoutMs: Int,
        allowPublicKeyAuth: Boolean
    ) {
        val banner = "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.UTF_8)
        writeAdbPacket(out, CMD_CNXN, VERSION, MAX_PAYLOAD, banner)

        var sentSignature = false
        var sentPubKey = false
        while (true) {
            val pkt = readAdbPacket(inp)
            when (pkt.command) {
                CMD_CNXN -> {
                    runCatching { sock.soTimeout = baseTimeoutMs }
                    return
                }
                CMD_AUTH -> {
                    if (pkt.arg0 != ADB_AUTH_TOKEN) {
                        throw IllegalStateException("Unexpected ADB auth subtype: ${pkt.arg0}")
                    }
                    when {
                        !sentSignature -> {
                            sentSignature = true
                            val sig = signAdbToken(privateKey, pkt.payload)
                            writeAdbPacket(out, CMD_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                        }
                        allowPublicKeyAuth && !sentPubKey -> {
                            sentPubKey = true
                            runCatching { sock.soTimeout = maxOf(baseTimeoutMs, 30_000) }
                            val keyPayload = (formatAdbPublicKeyLine(publicKey) + "\u0000")
                                .toByteArray(Charsets.UTF_8)
                            writeAdbPacket(out, CMD_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, keyPayload)
                        }
                        else -> throw IllegalStateException(
                            "ADB key rejected or confirmation timed out. Approve 'Allow USB debugging' on device."
                        )
                    }
                }
                else -> throw IllegalStateException(
                    "Unexpected ADB handshake command 0x${pkt.command.toString(16)}"
                )
            }
        }
    }

    private fun runShellV2Stream(
        inp: InputStream,
        out: OutputStream,
        allowed: AdbArgvPolicy.PolicyDecision.Allowed
    ): AdbExecResult {
        val localId = 1
        val service = "shell,v2,TERM=dumb:${allowed.shellCommand}\u0000".toByteArray(Charsets.UTF_8)
        writeAdbPacket(out, CMD_OPEN, localId, 0, service)

        var remoteId = 0
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exitCode = -1
        val v2Accum = ByteArrayOutputStream()

        while (true) {
            val pkt = try {
                readAdbPacket(inp)
            } catch (_: EOFException) {
                break
            }
            when (pkt.command) {
                CMD_OKAY -> {
                    if (remoteId == 0) remoteId = pkt.arg0
                }
                CMD_WRTE -> {
                    if (remoteId == 0) remoteId = pkt.arg0
                    writeAdbPacket(out, CMD_OKAY, localId, remoteId, ByteArray(0))
                    v2Accum.write(pkt.payload)
                    val bytes = v2Accum.toByteArray()
                    var offset = 0
                    while (offset + 5 <= bytes.size) {
                        val packetId = bytes[offset].toInt() and 0xFF
                        val len = ByteBuffer.wrap(bytes, offset + 1, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).int
                        if (len < 0 || offset + 5 + len > bytes.size) break
                        when (packetId) {
                            1 -> stdout.write(bytes, offset + 5, len)
                            2 -> stderr.write(bytes, offset + 5, len)
                            3 -> if (len >= 1) exitCode = bytes[offset + 5].toInt() and 0xFF
                        }
                        offset += 5 + len
                    }
                    if (offset > 0) {
                        v2Accum.reset()
                        if (offset < bytes.size) v2Accum.write(bytes, offset, bytes.size - offset)
                    }
                }
                CMD_CLSE -> {
                    if (remoteId != 0) {
                        runCatching { writeAdbPacket(out, CMD_CLSE, localId, remoteId, ByteArray(0)) }
                    }
                    break
                }
            }
        }

        return AdbExecResult(
            exitCode = exitCode,
            stdout = stdout.toString(Charsets.UTF_8.name()),
            stderr = stderr.toString(Charsets.UTF_8.name())
        )
    }

    suspend fun probeConnect(
        host: String,
        port: Int,
        transportMode: AdbTransportMode = AdbTransportMode.AUTO
    ): Result<String> = runCatching {
        val resolvedMode = when (transportMode) {
            AdbTransportMode.PLAIN_TCP -> "TCP"
            AdbTransportMode.TLS -> "TLS"
            AdbTransportMode.AUTO -> if (port == 5555) "TCP" else "TLS"
        }
        val res = executeAllowed(
            host = host,
            port = port,
            allowed = AdbArgvPolicy.PolicyDecision.Allowed(
                argv = listOf("getprop", "ro.product.model"),
                shellCommand = AdbArgvPolicy.formatShellCommand(listOf("getprop", "ro.product.model"))
            ),
            timeoutMs = 10_000,
            transportMode = transportMode
        )
        "transport=$resolvedMode, exit=${res.exitCode}, model=${res.stdout.trim()}"
    }

    private fun openPlainTcpSocket(host: String, port: Int, timeoutMs: Int): Socket =
        Socket().apply {
            soTimeout = timeoutMs
            connect(InetSocketAddress(host, port), timeoutMs)
        }

    private fun openTlsSocket(
        host: String,
        port: Int,
        privateKey: PrivateKey,
        cert: X509Certificate,
        timeoutMs: Int
    ): SSLSocket {
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String> =
                arrayOf("adb")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?): String =
                "adb"
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == "adb") arrayOf(cert) else null
            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == "adb") privateKey else null
        }
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslCtx = SSLContext.getInstance("TLSv1.3")
        sslCtx.init(arrayOf<KeyManager>(km), arrayOf<TrustManager>(tm), null)

        val tcp = Socket()
        tcp.soTimeout = timeoutMs
        tcp.connect(InetSocketAddress(host, port), timeoutMs)
        val ssl = sslCtx.socketFactory.createSocket(tcp, host, port, true) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        ssl.startHandshake()
        return ssl
    }

    data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    companion object {
        const val SERVICE_CONNECT = "_adb-tls-connect._tcp."
        const val SERVICE_PAIRING = "_adb-tls-pairing._tcp."

        const val CMD_CNXN = 0x4e584e43
        const val CMD_AUTH = 0x48545541
        const val CMD_OPEN = 0x4e45504f
        const val CMD_OKAY = 0x59414b4f
        const val CMD_CLSE = 0x45534c43
        const val CMD_WRTE = 0x45545257

        const val ADB_AUTH_TOKEN = 1
        const val ADB_AUTH_SIGNATURE = 2
        const val ADB_AUTH_RSAPUBLICKEY = 3

        const val ADB_RSA_NUM_WORDS = 64 // 2048-bit RSA = 64 x uint32
        const val VERSION = 0x01000001
        const val MAX_PAYLOAD = 1024 * 1024

        // ASN.1 DER DigestInfo prefix for SHA-1 (OID 1.3.14.3.2.26, NULL, OCTET STRING len 20)
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09,
            0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00,
            0x04, 0x14
        )

        /**
         * Encodes an RSA-2048 public key into AOSP `RSAPublicKey` 524-byte little-endian binary struct.
         */
        fun encodeAdbRsaPublicKey(publicKey: RSAPublicKey): ByteArray {
            val modulus = publicKey.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val mask32 = BigInteger.valueOf(0xFFFFFFFFL)
            val n0 = modulus.mod(r32)
            val n0inv = r32.subtract(n0.modInverse(r32)).mod(r32)
            val r = BigInteger.ONE.shiftLeft(ADB_RSA_NUM_WORDS * 32)
            val rr = r.multiply(r).mod(modulus)

            val bb = ByteBuffer.allocate(4 + 4 + ADB_RSA_NUM_WORDS * 4 * 2 + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(ADB_RSA_NUM_WORDS)
            bb.putInt(n0inv.toInt())
            for (i in 0 until ADB_RSA_NUM_WORDS) {
                bb.putInt(modulus.shiftRight(i * 32).and(mask32).toInt())
            }
            for (i in 0 until ADB_RSA_NUM_WORDS) {
                bb.putInt(rr.shiftRight(i * 32).and(mask32).toInt())
            }
            bb.putInt(publicKey.publicExponent.toInt())
            return bb.array()
        }

        fun formatAdbPublicKeyLine(publicKey: RSAPublicKey, comment: String = "dshn@android"): String {
            val b64 = Base64.getEncoder().encodeToString(encodeAdbRsaPublicKey(publicKey))
            return "$b64 $comment"
        }

        fun signAdbToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
            val digestInfo = SHA1_DIGEST_INFO_PREFIX + token
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, privateKey)
            return cipher.doFinal(digestInfo)
        }

        fun md5ColonFingerprint(rawStruct: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(rawStruct)
                .joinToString(":") { "%02x".format(it) }

        fun sha256Base64Fingerprint(rawStruct: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(rawStruct))

        fun writeAdbPacket(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
            val crc = payload.sumOf { (it.toInt() and 0xFF) }
            val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(cmd)
                putInt(arg0)
                putInt(arg1)
                putInt(payload.size)
                putInt(crc)
                putInt(cmd xor -1)
            }.array()
            synchronized(out) {
                out.write(hdr)
                if (payload.isNotEmpty()) out.write(payload)
                out.flush()
            }
        }

        fun readAdbPacket(inp: InputStream): AdbPacket {
            val hdr = readExact(inp, 24)
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = bb.int
            val arg0 = bb.int
            val arg1 = bb.int
            val len = bb.int
            bb.int // crc
            val magic = bb.int
            if (magic != (cmd xor -1)) {
                throw IllegalStateException("Invalid ADB magic header")
            }
            if (len < 0 || len > 4 * 1024 * 1024) {
                throw IllegalStateException("Invalid ADB payload size: $len")
            }
            val payload = if (len > 0) readExact(inp, len) else ByteArray(0)
            return AdbPacket(cmd, arg0, arg1, payload)
        }

        private fun readExact(inp: InputStream, count: Int): ByteArray {
            val buf = ByteArray(count)
            var off = 0
            while (off < count) {
                val n = inp.read(buf, off, count - off)
                if (n < 0) throw EOFException("Unexpected EOF in ADB stream")
                off += n
            }
            return buf
        }

        /**
         * Generates a minimal valid self-signed DER X.509 v3 certificate without external dependencies.
         */
        fun buildSelfSignedX509(pair: KeyPair, cn: String): X509Certificate {
            val cnVal = cn.removePrefix("CN=")
            val sigAlgOid = oid(intArrayOf(1, 2, 840, 113549, 1, 1, 11)) // sha256WithRSAEncryption
            val algId = derSeq(sigAlgOid + derNull())

            val cnAttr = derSeq(oid(intArrayOf(2, 5, 4, 3)) + derUtf8(cnVal))
            val name = derSeq(derSet(cnAttr))

            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val notBefore = derUtcTime(fmt.format(Date(System.currentTimeMillis() - 86_400_000L)))
            val notAfter = derUtcTime(fmt.format(Date(System.currentTimeMillis() + 3650L * 86_400_000L)))
            val validity = derSeq(notBefore + notAfter)

            val serial = BigInteger(63, java.security.SecureRandom()).abs().add(BigInteger.ONE)
            val versionTag = derTagged(0, derInteger(BigInteger.valueOf(2)))
            val tbs = derSeq(
                versionTag +
                    derInteger(serial) +
                    algId +
                    name +
                    validity +
                    name +
                    pair.public.encoded
            )

            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(pair.private)
                update(tbs)
            }.sign()

            val fullDer = derSeq(tbs + algId + derBitString(sig))
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(fullDer)) as X509Certificate
        }

        private fun oid(parts: IntArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(parts[0] * 40 + parts[1])
            for (i in 2 until parts.size) {
                var v = parts[i]
                val tmp = ByteArrayOutputStream()
                tmp.write(v and 0x7F)
                v = v ushr 7
                while (v > 0) {
                    tmp.write((v and 0x7F) or 0x80)
                    v = v ushr 7
                }
                val b = tmp.toByteArray()
                for (j in b.indices.reversed()) out.write(b[j].toInt())
            }
            return tlv(0x06, out.toByteArray())
        }

        private fun derNull() = byteArrayOf(0x05, 0x00)
        private fun derUtf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
        private fun derUtcTime(s: String) = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
        private fun derSeq(content: ByteArray) = tlv(0x30, content)
        private fun derSet(content: ByteArray) = tlv(0x31, content)
        private fun derTagged(tag: Int, content: ByteArray) = tlv(0xA0 or tag, content)
        private fun derInteger(v: BigInteger) = tlv(0x02, v.toByteArray())
        private fun derBitString(data: ByteArray) = tlv(0x03, byteArrayOf(0x00) + data)

        private fun tlv(tag: Int, content: ByteArray): ByteArray {
            val len = content.size
            val lenBytes = when {
                len < 128 -> byteArrayOf(len.toByte())
                len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
                len <= 0xFFFF -> byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), len.toByte())
                else -> byteArrayOf(
                    0x83.toByte(),
                    (len ushr 16).toByte(),
                    (len ushr 8).toByte(),
                    len.toByte()
                )
            }
            return byteArrayOf(tag.toByte()) + lenBytes + content
        }
    }
}
