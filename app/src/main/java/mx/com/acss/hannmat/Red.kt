package mx.com.acss.hannmat

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Conexiones HTTPS que completan la cadena del certificado por sí solas.
 *
 * Problema que resuelve: algunos hostings (InfinityFree) mandan solo el certificado del sitio y NO el
 * "intermedio" de la autoridad (Let's Encrypt, Google Trust...). Chrome lo descarga solo; Java/Android no, y
 * falla con "Trust anchor for certification path not found".
 *
 * Qué hace: si la validación normal falla, lee del certificado la dirección donde la autoridad publica su
 * intermedio (campo AIA "CA Issuers"), lo descarga y vuelve a validar con el almacén de confianza del
 * teléfono. La seguridad NO se relaja: el certificado descargado se comprueba por firma y la cadena tiene que
 * terminar en una raíz que el teléfono ya trae de fábrica.
 *
 * Esta clase no usa nada de android.*, así que también se puede probar en una PC normal.
 */
object Red {

    @Volatile private var fabrica: SSLSocketFactory? = null
    @Volatile private var carpetaCache: File? = null

    /** Abre la conexión; si es https le pone el validador que completa la cadena. */
    fun abrir(direccion: String, cache: File?): HttpURLConnection {
        val conn = URL(direccion).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) conn.sslSocketFactory = fabricaSsl(cache)
        return conn
    }

    @Synchronized
    private fun fabricaSsl(cache: File?): SSLSocketFactory {
        if (fabrica == null || carpetaCache != cache) {
            fabrica = crearFabrica(almacenDelSistema(), cache)
            carpetaCache = cache
        }
        return fabrica!!
    }

    /** El validador normal del teléfono (el que usa Chrome/Android por defecto), con las raíces del sistema. */
    private fun almacenDelSistema(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        // Importante en Android: con la configuración de red por dominio (network_security_config.xml), el validador
        // "por defecto" se niega a validar sin saber el dominio. Dándole explícitamente las raíces del sistema
        // se comporta como un validador normal y su validación sí funciona.
        tmf.init(raicesDelSistema())
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Raíces de confianza del sistema Android (sin las que el usuario haya instalado a mano). null = usar el almacén de Java. */
    private fun raicesDelSistema(): KeyStore? {
        return try {
            val android = KeyStore.getInstance("AndroidCAStore")
            android.load(null)
            val soloSistema = KeyStore.getInstance(KeyStore.getDefaultType())
            soloSistema.load(null, null)
            val alias = android.aliases()
            while (alias.hasMoreElements()) {
                val a = alias.nextElement()
                if (a.startsWith("system:")) {
                    val cert = android.getCertificate(a)
                    if (cert != null) soloSistema.setCertificateEntry(a, cert)
                }
            }
            // Si por alguna razón no se pudo separar, se usan todas (mejor eso que no confiar en nada)
            if (soloSistema.size() > 0) soloSistema else android
        } catch (e: Exception) {
            null // en una PC normal no existe AndroidCAStore
        }
    }

    /** Visible para pruebas: crea la fábrica SSL con un validador base distinto. */
    fun crearFabrica(base: X509TrustManager, cache: File?): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(ValidadorQueCompletaCadena(base, cache)), null)
        return ctx.socketFactory
    }

    class ValidadorQueCompletaCadena(
        private val base: X509TrustManager,
        private val cache: File?
    ) : X509TrustManager {

        override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            base.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                base.checkServerTrusted(chain, authType)
            } catch (original: CertificateException) {
                val completa = completar(chain, authType) ?: throw original
                base.checkServerTrusted(completa, authType) // si aún no es de confianza, lanza el error
            }
        }

        /** Va subiendo por los emisores (máx. 5) hasta que el teléfono confíe en la cadena. */
        private fun completar(chain: Array<X509Certificate>, authType: String): Array<X509Certificate>? {
            val lista = chain.toMutableList()
            var actual = lista.last()
            repeat(5) {
                if (actual.subjectX500Principal == actual.issuerX500Principal) return null // ya es una raíz
                val url = direccionDelEmisor(actual) ?: return null
                val emisor = obtenerEmisor(url, actual) ?: return null
                lista.add(emisor)
                actual = emisor
                val intento = lista.toTypedArray()
                try {
                    base.checkServerTrusted(intento, authType)
                    return intento
                } catch (e: CertificateException) {
                    // todavía falta un eslabón: seguir subiendo
                }
            }
            return null
        }

        /** Descarga (o toma del caché) el certificado del emisor y comprueba que de verdad firmó a [hijo]. */
        private fun obtenerEmisor(url: String, hijo: X509Certificate): X509Certificate? {
            val archivo = cache?.let { File(it, "aia-" + sha256(url) + ".cer") }
            val delCache = archivo?.takeIf { it.isFile }?.let { leer(it.readBytes(), hijo) }
            if (delCache != null) return delCache
            val bytes = descargar(url) ?: return null
            val cert = leer(bytes, hijo) ?: return null
            try {
                archivo?.let { it.parentFile?.mkdirs(); it.writeBytes(bytes) }
            } catch (e: Exception) { /* el caché es opcional */ }
            return cert
        }

        private fun leer(bytes: ByteArray, hijo: X509Certificate): X509Certificate? {
            val candidatos = try {
                CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes))
            } catch (e: Exception) { return null }
            for (c in candidatos) {
                val x = c as? X509Certificate ?: continue
                if (x.subjectX500Principal != hijo.issuerX500Principal) continue
                try {
                    hijo.verify(x.publicKey)
                    x.checkValidity()
                    return x
                } catch (e: Exception) { /* no es el que firmó: probar el siguiente */ }
            }
            return null
        }

        private fun descargar(url: String): ByteArray? {
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
            return try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                try {
                    if (c.responseCode !in 200..299) return null
                    c.inputStream.use { it.readNBytesCompat(65536) }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    // ---- Lectura de la extensión AIA (1.3.6.1.5.5.7.1.1), sin librerías externas ----

    /** Dirección "CA Issuers" del certificado (de dónde bajar al emisor), o null si no trae. */
    fun direccionDelEmisor(cert: X509Certificate): String? {
        val ext = cert.getExtensionValue("1.3.6.1.5.5.7.1.1") ?: return null
        // OID de caIssuers: 1.3.6.1.5.5.7.48.2
        val oid = byteArrayOf(0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)
        var i = 0
        while (i <= ext.size - oid.size) {
            var igual = true
            for (k in oid.indices) if (ext[i + k] != oid[k]) { igual = false; break }
            if (igual) {
                var p = i + oid.size
                if (p < ext.size && (ext[p].toInt() and 0xFF) == 0x86) { // GeneralName: uniformResourceIdentifier
                    p++
                    var largo = ext[p++].toInt() and 0xFF
                    if (largo and 0x80 != 0) {
                        val n = largo and 0x7F
                        largo = 0
                        repeat(n) { largo = (largo shl 8) or (ext[p++].toInt() and 0xFF) }
                    }
                    if (p + largo <= ext.size) return String(ext, p, largo, Charsets.US_ASCII)
                }
            }
            i++
        }
        return null
    }
}
