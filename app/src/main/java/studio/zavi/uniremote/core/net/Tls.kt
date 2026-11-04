package studio.zavi.uniremote.core.net

import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TVs use self-signed certificates on the local network, so there is no CA to check against.
 * Trust is established by pairing (a code on screen or an Allow prompt), not by the certificate chain.
 */
object Tls {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val trustAllContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
    }

    /** TLS context that presents a client certificate (Android TV needs this). */
    fun clientCertContext(key: PrivateKey, cert: X509Certificate): SSLContext {
        val keyManager = object : X509ExtendedKeyManager() {
            private val alias = "client"
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = alias
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?) = alias
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(alias)
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = key
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trustAll), SecureRandom()) }
    }

    /**
     * Opens a TCP connection and wraps it in TLS, finishing the handshake before returning.
     * A failure after TCP connected is always reported as [SSLException]: a TV that rejects
     * our certificate often just drops the socket mid-handshake ("broken pipe"), and callers
     * need to tell that apart from "nothing listening".
     */
    fun connect(context: SSLContext, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.tcpNoDelay = true
            raw.soTimeout = timeoutMs
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
        try {
            val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.useClientMode = true
            ssl.startHandshake()
            return ssl
        } catch (e: SSLException) {
            runCatching { raw.close() }
            throw e
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw SSLException("TLS handshake with $host:$port failed: ${e.message}", e)
        }
    }
}
