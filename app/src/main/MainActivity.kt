package mx.com.acss.hannmat

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Pantalla única de configuración (hecha por código, sin layouts XML ni librerías externas). */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var estado: TextView
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var swActivo: Switch
    private lateinit var cbGrupos: CheckBox
    private lateinit var etPermitidos: EditText
    private lateinit var recientes: LinearLayout
    private lateinit var bitacora: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        construirPantalla()
        aplicarEnlace(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        aplicarEnlace(intent)
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    /** hannmat://config?url=...&key=...  -> deja la app configurada con un toque desde el panel. */
    private fun aplicarEnlace(i: Intent?) {
        val u: Uri = i?.data ?: return
        if (u.scheme != "hannmat") return
        u.getQueryParameter("url")?.let { prefs.url = it }
        u.getQueryParameter("key")?.let { prefs.key = it }
        Toast.makeText(this, "Configuración aplicada", Toast.LENGTH_SHORT).show()
        refrescar()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun titulo(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun nota(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(0xFF555555.toInt())
    }

    private fun boton(t: String, alTocar: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { alTocar() }
    }

    private fun construirPantalla() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        val scroll = ScrollView(this)
        scroll.addView(raiz, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        raiz.addView(TextView(this).apply {
            text = "Hannmat para WhatsApp"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        raiz.addView(nota("Contesta solo a los contactos y grupos que elijas, con las vacantes de tu base de datos."))

        estado = TextView(this).apply { textSize = 14f; setPadding(0, dp(10), 0, 0) }
        raiz.addView(estado)
        raiz.addView(boton("1. Dar permiso de acceso a notificaciones") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        raiz.addView(boton("2. Quitar ahorro de batería a esta app") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        raiz.addView(titulo("Servidor"))
        etUrl = EditText(this).apply {
            hint = "https://tusitio.com/api/bot.php?i=1"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.url)
        }
        raiz.addView(etUrl)
        etKey = EditText(this).apply {
            hint = "Clave (la ves en el panel > App WhatsApp)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.key)
        }
        raiz.addView(etKey)

        raiz.addView(titulo("A quién contesta"))
        raiz.addView(nota("Escribe un nombre por renglón, igual que sale en WhatsApp (contacto o grupo). También puedes tocar los chats detectados."))
        etPermitidos = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP
            setText(prefs.allowed.joinToString("\n"))
        }
        raiz.addView(etPermitidos)
        cbGrupos = CheckBox(this).apply {
            text = "En grupos, contestar solo si el mensaje empieza con \"Hannmat\""
            isChecked = prefs.groupTrigger
        }
        raiz.addView(cbGrupos)

        raiz.addView(titulo("Chats detectados (toca para agregar)"))
        recientes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(recientes)

        swActivo = Switch(this).apply {
            text = "Hannmat encendido  "
            isChecked = prefs.enabled
            setPadding(0, dp(16), 0, dp(8))
        }
        raiz.addView(swActivo)
        raiz.addView(boton("Guardar") { guardar(); Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show(); refrescar() })
        raiz.addView(boton("Probar conexión con el servidor") {
            guardar()
            Toast.makeText(this, "Probando...", Toast.LENGTH_SHORT).show()
            thread {
                val r = WhatsAppListener.probar(prefs)
                runOnUiThread { android.app.AlertDialog.Builder(this).setMessage(r).setPositiveButton("OK", null).show() }
            }
        })

        raiz.addView(titulo("Actividad reciente"))
        bitacora = TextView(this).apply { textSize = 12f }
        raiz.addView(bitacora)
    }

    private fun guardar() {
        prefs.url = etUrl.text.toString()
        prefs.key = etKey.text.toString()
        prefs.allowed = etPermitidos.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        prefs.groupTrigger = cbGrupos.isChecked
        prefs.enabled = swActivo.isChecked
    }

    private fun permisoConcedido(): Boolean {
        val lista = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return lista.contains(packageName)
    }

    private fun refrescar() {
        estado.text = (if (permisoConcedido()) "✅ Permiso de notificaciones: concedido" else "⚠️ Falta el permiso de notificaciones") +
            "\n" + (if (prefs.enabled) "🟢 Hannmat encendido" else "⚪ Hannmat apagado")
        recientes.removeAllViews()
        val permitidos = prefs.allowed.map { Prefs.norm(it) }
        val porAgregar = prefs.recent.filter { Prefs.norm(it) !in permitidos }
        if (porAgregar.isEmpty()) {
            recientes.addView(nota("Aquí saldrán los chats que te escriban cuando la app esté funcionando."))
        }
        for (nombre in porAgregar) {
            recientes.addView(boton("+ $nombre") {
                val actuales = etPermitidos.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                etPermitidos.setText((actuales + nombre).joinToString("\n"))
                guardar()
                refrescar()
            })
        }
        bitacora.text = prefs.log.joinToString("\n").ifEmpty { "Sin actividad todavía." }
    }
}
