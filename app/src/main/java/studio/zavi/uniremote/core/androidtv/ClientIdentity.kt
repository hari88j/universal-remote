package studio.zavi.uniremote.core.androidtv

import studio.zavi.uniremote.core.KeyValueStore
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The phone's identity towards Android TVs: an RSA key and a self-signed certificate.
 * The TV remembers this certificate after pairing, so it is created once and kept.
 */
class ClientIdentity(private val store: KeyValueStore) {
    @Volatile
    private var cached: Pair<PrivateKey, X509Certificate>? = null

    /** Loads the saved identity or creates one (RSA key generation takes about a second on a phone). */
    @Synchronized
    fun get(): Pair<PrivateKey, X509Certificate> {
        cached?.let { return it }
        val loaded = runCatching { load() }.getOrNull()
        val identity = loaded ?: create().also { save(it) }
        cached = identity
        return identity
    }

    private fun load(): Pair<PrivateKey, X509Certificate>? {
        val keyB64 = store.get(KEY_PRIVATE) ?: return null
        val certB64 = store.get(KEY_CERT) ?: return null
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyB64)))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.getDecoder().decode(certB64).inputStream()) as X509Certificate
        return key to cert
    }

    private fun save(identity: Pair<PrivateKey, X509Certificate>) {
        store.put(KEY_PRIVATE, Base64.getEncoder().encodeToString(identity.first.encoded))
        store.put(KEY_CERT, Base64.getEncoder().encodeToString(identity.second.encoded))
    }

    private fun create(): Pair<PrivateKey, X509Certificate> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val der = SelfSignedCert.build(pair.public.encoded, pair.private, "atvremote")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        return pair.private to cert
    }

    companion object {
        private const val KEY_PRIVATE = "atv_client_key"
        private const val KEY_CERT = "atv_client_cert"
    }
}

/**
 * Builds a DER-encoded X.509 v3 self-signed certificate with only the JDK
 * (no BouncyCastle). Mirrors what androidtvremote2 generates:
 * CN=<name>, 10 years validity, CA:TRUE, SAN DNS:<name>, SHA256withRSA.
 */
object SelfSignedCert {
    private val OID_SHA256_RSA = intArrayOf(1, 2, 840, 113549, 1, 1, 11)
    private val OID_CN = intArrayOf(2, 5, 4, 3)
    private val OID_BASIC_CONSTRAINTS = intArrayOf(2, 5, 29, 19)
    private val OID_SAN = intArrayOf(2, 5, 29, 17)

    fun build(subjectPublicKeyInfo: ByteArray, privateKey: PrivateKey, commonName: String, now: Date = Date()): ByteArray {
        val algorithm = seq(oid(OID_SHA256_RSA), byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(oid(OID_CN), tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val notBefore = Date(now.time - 24L * 3600 * 1000)
        val notAfter = Date(now.time + 10L * 365 * 24 * 3600 * 1000)
        val validity = seq(time(notBefore), time(notAfter))

        val basicConstraints = seq(
            oid(OID_BASIC_CONSTRAINTS),
            tlv(0x04, seq(byteArrayOf(0x01, 0x01, 0xFF.toByte()), integer(BigInteger.ZERO))),
        )
        val san = seq(
            oid(OID_SAN),
            tlv(0x04, seq(tlv(0x82, commonName.toByteArray(Charsets.US_ASCII)))),
        )
        val extensions = tlv(0xA3, seq(basicConstraints, san))

        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))), // version v3
            integer(BigInteger.valueOf(1000)),
            algorithm,
            name,
            validity,
            name,
            subjectPublicKeyInfo,
            extensions,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(tbs)
            sign()
        }
        return seq(tbs, algorithm, tlv(0x03, byteArrayOf(0x00) + signature))
    }

    private fun time(date: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = date }.get(java.util.Calendar.YEAR)
        return if (year < 2050) {
            val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }
            tlv(0x17, f.format(date).toByteArray(Charsets.US_ASCII))
        } else {
            val f = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utc }
            tlv(0x18, f.format(date).toByteArray(Charsets.US_ASCII))
        }
    }

    private fun integer(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())

    private fun oid(parts: IntArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = ArrayList<Int>()
            stack.add(v and 0x7F)
            v = v ushr 7
            while (v > 0) {
                stack.add((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            for (j in stack.indices.reversed()) body.write(stack[j])
        }
        return tlv(0x06, body.toByteArray())
    }

    private fun seq(vararg items: ByteArray): ByteArray = tlv(0x30, concat(items))

    private fun set(vararg items: ByteArray): ByteArray = tlv(0x31, concat(items))

    private fun concat(items: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in items) out.write(i)
        return out.toByteArray()
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(value.size + 6)
        out.write(tag)
        val len = value.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xFF -> { out.write(0x81); out.write(len) }
            len <= 0xFFFF -> { out.write(0x82); out.write(len ushr 8); out.write(len and 0xFF) }
            else -> { out.write(0x83); out.write(len ushr 16); out.write((len ushr 8) and 0xFF); out.write(len and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }
}
