package mx.com.acss.hannmat

import android.content.Context
import java.text.Normalizer

/** Configuración guardada en el teléfono (dirección del servidor, clave, contactos permitidos...). */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("hannmat", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) { sp.edit().putString("url", v.trim()).apply() }

    var key: String
        get() = sp.getString("key", "") ?: ""
        set(v) { sp.edit().putString("key", v.trim()).apply() }

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    /** En grupos, solo contestar mensajes que empiecen con "Hannmat". */
    var groupTrigger: Boolean
        get() = sp.getBoolean("groupTrigger", true)
        set(v) { sp.edit().putBoolean("groupTrigger", v).apply() }

    /** Nombres de contactos y grupos (tal como salen en WhatsApp) a los que Hannmat SÍ contesta. */
    var allowed: List<String>
        get() = (sp.getString("allowed", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) { sp.edit().putString("allowed", v.joinToString("\n")).apply() }

    val recent: List<String>
        get() = (sp.getString("recent", "") ?: "").split("\n").filter { it.isNotEmpty() }

    /** Guarda los últimos chats que mandaron notificación, para elegirlos con un toque en la pantalla. */
    fun addRecent(name: String) {
        val lista = recent.toMutableList()
        lista.remove(name)
        lista.add(0, name)
        sp.edit().putString("recent", lista.take(30).joinToString("\n")).apply()
    }

    val log: List<String>
        get() = (sp.getString("log", "") ?: "").split("\n").filter { it.isNotEmpty() }

    fun log(linea: String) {
        val hora = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val lista = log.toMutableList()
        lista.add(0, "$hora  ${linea.replace("\n", " ").take(160)}")
        sp.edit().putString("log", lista.take(25).joinToString("\n")).apply()
    }

    companion object {
        fun norm(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()
    }
}
