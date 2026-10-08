package mx.com.acss.hannmat

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Lee las notificaciones de WhatsApp y, si el chat está en la lista permitida, le pregunta a tu servidor
 * (api/bot.php, que usa la misma lógica de Hannmat y la base de datos en vivo) y contesta desde la propia notificación.
 */
class WhatsAppListener : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val vistos = HashMap<String, String>()        // notificación -> último mensaje ya atendido
    private val ultimaRespuesta = HashMap<String, String>() // chat -> última respuesta enviada (para no contestarnos solos)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val prefs = Prefs(this)

        val esGrupo = extras.getBoolean("android.isGroupConversation", false)
        val titulo = (extras.getCharSequence("android.conversationTitle") ?: extras.getCharSequence(Notification.EXTRA_TITLE))
            ?.toString()?.trim() ?: return
        if (titulo.isEmpty() || titulo.equals("WhatsApp", ignoreCase = true)) return

        // Último mensaje de la conversación
        var texto: String? = null
        var remitente: String? = null
        @Suppress("DEPRECATION")
        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val b = msgs.last() as? Bundle
            texto = b?.getCharSequence("text")?.toString()
            remitente = b?.getCharSequence("sender")?.toString()
        }
        if (texto.isNullOrBlank()) texto = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (texto.isNullOrBlank()) return
        if (esGrupo && remitente == null && texto.contains(": ")) {
            remitente = texto.substringBefore(": ")
            texto = texto.substringAfter(": ")
        }

        // Resúmenes ("3 mensajes nuevos") y mensajes propios
        if (Regex("""^\d+\s+(mensajes?|messages?)(\s+nuevos?|\s+new)?.*""", RegexOption.IGNORE_CASE).matches(texto.trim())) return
        if (remitente != null && Prefs.norm(remitente) in listOf("tu", "you", "yo")) return
        if (ultimaRespuesta[titulo] == texto) return

        prefs.addRecent(titulo) // para poder elegirlo con un toque en la app

        if (!prefs.enabled || prefs.url.isBlank() || prefs.key.isBlank()) return
        val permitido = prefs.allowed.any { val a = Prefs.norm(it); val t = Prefs.norm(titulo); t == a || t.contains(a) }
        if (!permitido) return

        var mensaje = texto.trim()
        if (esGrupo && prefs.groupTrigger) {
            val m = Regex("""^\s*@?hann?mat\b[\s,:;\-]*""", RegexOption.IGNORE_CASE).find(mensaje) ?: return
            mensaje = mensaje.substring(m.range.last + 1).trim().ifEmpty { "hola" }
        }

        val firma = "${n.`when`}|$texto"
        if (vistos[sbn.key] == firma) return
        vistos[sbn.key] = firma

        val accion = buscarAccionRespuesta(n)
        if (accion == null) {
            prefs.log("No encontré cómo responder a \"$titulo\" (¿notificación sin botón Responder?)")
            return
        }
        val contacto = if (esGrupo) "grupo:$titulo|${remitente ?: "?"}" else "chat:$titulo"

        executor.execute {
            try {
                val resp = consultarServidor(prefs, contacto, mensaje)
                if (!resp.isNullOrBlank()) {
                    Thread.sleep(1500) // pausa corta para que no se sienta robótico
                    enviarRespuesta(accion, resp)
                    ultimaRespuesta[titulo] = resp
                    prefs.log("Respondí a \"$titulo\"")
                }
            } catch (e: Exception) {
                prefs.log("Error con \"$titulo\": ${e.message}")
            }
        }
    }

    private fun buscarAccionRespuesta(n: Notification): Notification.Action? {
        n.actions?.forEach { a ->
            if (a.remoteInputs != null && a.remoteInputs.isNotEmpty()) return a
        }
        Notification.WearableExtender(n).actions.forEach { a ->
            if (a.remoteInputs != null && a.remoteInputs.isNotEmpty()) return a
        }
        return null
    }

    private fun enviarRespuesta(accion: Notification.Action, texto: String) {
        val intent = Intent()
        val datos = Bundle()
        for (ri in accion.remoteInputs) datos.putCharSequence(ri.resultKey, texto)
        RemoteInput.addResultsToIntent(accion.remoteInputs, intent, datos)
        accion.actionIntent.send(this, 0, intent)
    }

    private fun consultarServidor(p: Prefs, contacto: String, mensaje: String): String? {
        val conn = URL(p.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("X-Bot-Key", p.key)
            val cuerpo = "contacto=${enc(contacto)}&mensaje=${enc(mensaje)}&key=${enc(p.key)}"
            conn.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            val codigo = conn.responseCode
            val flujo = if (codigo in 200..299) conn.inputStream else conn.errorStream
            val txt = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(txt) } catch (e: Exception) {
                p.log("El servidor no devolvió JSON (código $codigo). Si es InfinityFree, la dirección debe terminar en ?i=1")
                return null
            }
            if (!json.optBoolean("ok", false)) {
                p.log("Servidor: ${json.optString("respuesta")}")
                return null
            }
            return json.optString("respuesta").ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Prueba de conexión desde la pantalla principal. Devuelve la respuesta de Hannmat o el motivo del error. */
        fun probar(p: Prefs): String {
            return try {
                val conn = URL(p.url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.setRequestProperty("X-Bot-Key", p.key)
                conn.outputStream.use { it.write("contacto=prueba-app&mensaje=hola&reset=1&key=${enc(p.key)}".toByteArray(Charsets.UTF_8)) }
                val codigo = conn.responseCode
                val txt = (if (codigo in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val json = try { JSONObject(txt) } catch (e: Exception) {
                    return "El servidor no devolvió JSON (código $codigo). Si es InfinityFree, la dirección debe terminar en ?i=1"
                }
                if (json.optBoolean("ok", false)) "Conexión correcta. Hannmat respondió:\n" + json.optString("respuesta")
                else "El servidor respondió: " + json.optString("respuesta")
            } catch (e: Exception) {
                "No se pudo conectar: ${e.message}"
            }
        }
    }
}
