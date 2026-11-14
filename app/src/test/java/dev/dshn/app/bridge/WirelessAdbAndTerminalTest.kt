package dev.dshn.app.bridge

import dev.dshn.app.ui.AnsiTerminalBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey

class WirelessAdbAndTerminalTest {

    @Test
    fun selfSignedAdbX509CertificateIsValid() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = kpg.generateKeyPair()
        val cert = WirelessAdbClient.buildSelfSignedX509(pair, "CN=dshn-device-bridge")
        cert.verify(pair.public)
        assertTrue(cert.subjectX500Principal.name.contains("dshn-device-bridge"))
    }

    @Test
    fun adbRsaPublicKeyEncodingAndTokenSignatureMatchAospSpec() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = kpg.generateKeyPair()
        val pub = pair.public as RSAPublicKey

        val encoded = WirelessAdbClient.encodeAdbRsaPublicKey(pub)
        assertEquals(524, encoded.size)

        val bb = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bb.int) // len = 64 uint32 words
        val n0invUnsigned = bb.int.toLong() and 0xFFFFFFFFL
        val modBytesLe = ByteArray(256).also { bb.get(it) }
        val rrBytesLe = ByteArray(256).also { bb.get(it) }
        assertEquals(pub.publicExponent.toInt(), bb.int)

        val decodedN = BigInteger(1, modBytesLe.reversedArray())
        assertEquals(pub.modulus, decodedN)

        val twoPow32 = BigInteger.ONE.shiftLeft(32)
        val check = BigInteger.valueOf(n0invUnsigned).multiply(pub.modulus).add(BigInteger.ONE).mod(twoPow32)
        assertEquals(BigInteger.ZERO, check)

        val expectedRr = BigInteger.ONE.shiftLeft(2048).pow(2).mod(pub.modulus)
        val decodedRr = BigInteger(1, rrBytesLe.reversedArray())
        assertEquals(expectedRr, decodedRr)

        val msg = "adb-handshake-nonce-check".toByteArray(Charsets.UTF_8)
        val token = MessageDigest.getInstance("SHA-1").digest(msg)
        val sig = WirelessAdbClient.signAdbToken(pair.private, token)
        assertEquals(256, sig.size)

        val verifier = Signature.getInstance("SHA1withRSA").apply {
            initVerify(pub)
            update(msg)
        }
        assertTrue(verifier.verify(sig))
    }

    @Test
    fun ansiTerminalBufferStripsEscapesAndUpdatesLines() {
        val buf = AnsiTerminalBuffer(cols = 40, rows = 10)
        buf.append("\u001B[32mhello\u001B[0m world\r\nsecond\b\b line")
        val rendered = buf.renderText()
        assertTrue(rendered.contains("hello world"))
        assertTrue(rendered.contains("seco line"))

        buf.append("\u001B[2J\u001B[Hfresh")
        assertEquals("fresh", buf.renderText().trim())
    }
}
