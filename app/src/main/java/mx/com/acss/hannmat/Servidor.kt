package mx.com.acss.hannmat

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLException

/** Habla con api/bot.php de tu sitio. Los errores se devuelven ya explicados en español sencillo. */
object Servidor {

    /** ok = true: [texto] es lo que contestó Hannmat. ok = false: [texto] explica qué pasó. */
    class Respuesta(val ok: Boolean, val texto: String)

    /**
     * Identificación de navegador. El hosting gratuito (InfinityFree) suele bloquear con error 403, ANTES de llegar
     * al PHP, las peticiones que se identifican como "Dalvik/okhttp/Java" (lo normal en una app). Con esta
     * identificación la petición se ve como la de Chrome en un celular, igual que cuando abres el sitio.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** "https://misitio.com" a partir de la dirección completa (para los encabezados Origin y Referer). */
    private fun origenDe(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return ""
        val fin = url.indexOf('/', i + 3)
        return if (fin < 0) url else url.substring(0, fin)
    }

    /** Texto corto y legible de lo que contestó el servidor (sin etiquetas HTML) para mostrarlo en los errores. */
    private fun resumenCuerpo(txt: String): String =
        txt.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(140)

    /** Acepta "misitio.com/api/bot.php" y lo deja como "https://misitio.com/api/bot.php?i=1". */
    fun normalizarUrl(entrada: String): String {
        var s = entrada.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        if (!s.contains("?")) s += "?i=1" // el hosting gratuito bloquea a las apps si falta
        return s
    }

    /** Dirección del panel de administración, sacada de la dirección del servidor (o null). */
    fun urlPanel(url: String): String? {
        val u = normalizarUrl(url)
        val i = u.indexOf("/api/")
        return if (i > 8) u.substring(0, i + 1) + "admin/bot-whatsapp.php" else null
    }

    // ---------------------------------------------------------------------------------------------------------
    // Verificación del hosting gratuito (InfinityFree)
    // Antes de dejar pasar a un visitante, el hosting le manda una paginita con un acertijo en JavaScript: si
    // quien visita lo resuelve y guarda la cookie "__test", lo deja entrar. Un navegador lo hace solo; una app no,
    // y por eso recibía 403. Aquí la app hace lo mismo que haría el navegador: lee el acertijo (un descifrado AES
    // con tres números que vienen en la propia página), guarda la cookie y repite la petición.
    // ---------------------------------------------------------------------------------------------------------

    @Volatile private var cookieTest: String? = null

    private class Crudo(val codigo: Int, val cuerpo: String)

    private fun esDesafio(txt: String) = txt.contains("slowAES", true) && txt.contains("toNumbers", true)

    private fun esJson(txt: String): Boolean = try { JSONObject(txt); true } catch (e: Exception) { false }

    private fun deHex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** Resuelve el acertijo de la página de verificación. Devuelve el valor de la cookie __test, o null si no pudo. */
    private fun resolverDesafio(txt: String): String? {
        if (!esDesafio(txt)) return null
        val nums = Regex("toNumbers\\(\"([0-9a-fA-F]+)\"\\)").findAll(txt).map { it.groupValues[1] }.toList()
        if (nums.size < 3) return null
        return try {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(deHex(nums[0]), "AES"), IvParameterSpec(deHex(nums[1])))
            c.doFinal(deHex(nums[2])).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        } catch (e: Exception) {
            null
        }
    }

    /** Una petición HTTP con la identificación de navegador. Si [cuerpo] no es null se manda como POST. */
    private fun llamar(ctx: Context, url: String, cuerpo: String?, key: String?, cookie: String?, ligero: Boolean = false): Crudo {
        var conn: HttpURLConnection? = null
        try {
            conn = Red.abrir(url, File(ctx.filesDir, "aia"))
            conn.requestMethod = if (cuerpo != null) "POST" else "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            // Cada petición en su propia conexión: así no se arrastra nada de la petición anterior (la de la verificación).
            conn.setRequestProperty("Connection", "close")
            val origen = origenDe(url)
            if (!ligero) {
                conn.setRequestProperty("Accept-Language", "es-MX,es;q=0.9,en;q=0.8")
                if (origen.isNotEmpty()) {
                    conn.setRequestProperty("Origin", origen)
                    conn.setRequestProperty("Referer", "$origen/")
                }
            }
            if (cookie != null) conn.setRequestProperty("Cookie", "__test=$cookie")
            if (key != null) conn.setRequestProperty("X-Bot-Key", key)
            if (cuerpo != null) {
                val bytes = cuerpo.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(bytes) }
            }
            val codigo = conn.responseCode
            val flujo = if (codigo in 200..299) conn.inputStream else conn.errorStream
            val txt = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Crudo(codigo, txt)
        } finally {
            conn?.disconnect()
        }
    }

    /** Consigue la cookie de verificación: primero con lo que ya contestó el servidor y, si no sirve, pidiendo la página sin "?i=1". */
    private fun conseguirCookie(ctx: Context, url: String, cuerpoPrevio: String): String? {
        resolverDesafio(cuerpoPrevio)?.let { return it }
        val origen = origenDe(url)
        val candidatas = listOf(url.substringBefore("?"), if (origen.isNotEmpty()) "$origen/" else "")
        for (u in candidatas) {
            if (u.isBlank()) continue
            try {
                resolverDesafio(llamar(ctx, u, null, null, null).cuerpo)?.let { return it }
            } catch (e: Exception) { /* se prueba con la siguiente */ }
        }
        return null
    }

    fun enviar(ctx: Context, p: Prefs, contacto: String, mensaje: String, reset: Boolean = false): Respuesta {
        val url = normalizarUrl(p.url)
        if (url.isBlank()) return Respuesta(false, "Falta la dirección del servidor.")
        if (p.key.isBlank()) return Respuesta(false, "Falta la clave de la app.")
        try {
            val cuerpo = "contacto=${enc(contacto)}&mensaje=${enc(mensaje)}&key=${enc(p.key)}" + (if (reset) "&reset=1" else "")
            var r = llamar(ctx, url, cuerpo, p.key, cookieTest)

            // Si el hosting contestó con su verificación (o con 400/403 sin datos de Hannmat), se resuelve y se repite UNA vez.
            // El 400 "Bad Request openresty" lo da el hosting cuando el POST llega sin la cookie de verificación.
            var intento = ""
            if (!esJson(r.cuerpo) && (r.codigo == 400 || r.codigo == 403 || esDesafio(r.cuerpo))) {
                val primero = r.codigo
                val cookie = conseguirCookie(ctx, url, r.cuerpo)
                if (cookie != null) {
                    cookieTest = cookie
                    r = llamar(ctx, url, cuerpo, p.key, cookie)
                    var detalleIntentos = "1er intento: código $primero; verificación resuelta; 2do intento: código ${r.codigo}"
                    if (!esJson(r.cuerpo) && r.codigo == 400) {
                        // Último recurso: la misma petición con lo mínimo (sin Origin, Referer ni idioma).
                        r = llamar(ctx, url, cuerpo, p.key, cookie, true)
                        detalleIntentos += "; 3er intento (versión mínima): código ${r.codigo}"
                    }
                    intento = "\n($detalleIntentos)"
                } else {
                    intento = "\n(1er intento: código $primero; no se pudo resolver la verificación del hosting)"
                }
            }

            val codigo = r.codigo
            val txt = r.cuerpo
            val json: JSONObject? = try { JSONObject(txt) } catch (e: Exception) { null }
            if (json == null) {
                val detalle = resumenCuerpo(txt)
                val aviso = if (detalle.isNotEmpty()) "\nEl servidor dijo: «$detalle»" else ""
                val aviso2 = aviso + intento
                return Respuesta(false, when {
                    codigo == 404 -> "No se encontró esa dirección (error 404). Revisa que termine en /api/bot.php?i=1."
                    codigo >= 500 -> "Tu servidor tuvo un problema (código $codigo). Intenta de nuevo en unos minutos.$aviso2"
                    codigo == 403 -> "El hosting sigue bloqueando la petición (código 403), incluso después de resolver su verificación. Revisa que la dirección termine en /api/bot.php?i=1.$aviso2"
                    else -> "El servidor no contestó con datos de Hannmat (código $codigo). Si tu hosting es InfinityFree, la dirección debe terminar en ?i=1.$aviso2"
                })
            }
            if (codigo == 401) {
                return Respuesta(false, "La clave no coincide. Cópiala otra vez desde el panel (Admin → App WhatsApp).")
            }
            if (!json.optBoolean("ok", false)) {
                return Respuesta(false, "El servidor respondió: " + json.optString("respuesta", "error desconocido"))
            }
            return Respuesta(true, json.optString("respuesta"))
        } catch (e: Exception) {
            return Respuesta(false, explicar(e))
        }
    }

    /** Prueba desde la pantalla principal. Guarda en [Prefs.conexionOk] si funcionó. */
    fun probar(ctx: Context, p: Prefs): Respuesta {
        val r = enviar(ctx, p, "prueba-app", "hola", reset = true)
        p.conexionOk = r.ok
        return r
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Convierte una excepción técnica en una explicación que cualquiera entiende. */
    fun explicar(e: Throwable): String {
        val cadena = generateSequence(e) { it.cause }.take(8).toList()
        val texto = cadena.joinToString(" | ") { "${it.javaClass.simpleName} ${it.message ?: ""}" }
        return when {
            texto.contains("Cleartext", true) || texto.contains("not permitted", true) ->
                "La dirección debe empezar con https:// (por seguridad no se permite http)."
            cadena.any { it is UnknownHostException } ->
                "No se encontró el servidor. Revisa la dirección y que el celular tenga internet."
            cadena.any { it is SocketTimeoutException } ->
                "El servidor tardó demasiado en contestar. Intenta de nuevo."
            cadena.any { it is ConnectException } ->
                "No se pudo conectar con el servidor. Revisa tu internet o la dirección."
            texto.contains("Trust anchor", true) || texto.contains("CertPath", true) || texto.contains("certificate", true) ->
                "El celular no confía en el certificado de seguridad de ese sitio. Revisa que la fecha y hora del celular estén en automático y que el certificado HTTPS del sitio esté vigente."
            cadena.any { it is SSLException } ->
                "No se pudo establecer la conexión segura (HTTPS) con el sitio."
            else -> "No se pudo conectar: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
