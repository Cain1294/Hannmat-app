package mx.com.acss.hannmat

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Pantalla principal de Hannmat. Hecha por código (sin librerías externas) para que compile igual en GitHub.
 * Orden pensado para alguien que la usa por primera vez:
 *   1) Estado (encendido/apagado) · 2) Pasos de configuración · 3) Conexión · 4) A quién contesta · 5) Actividad · 6) Ayuda
 * Todo se guarda solo, no hay botón "Guardar".
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var ignorarSwitch = false
    private var probando = false

    private lateinit var scroll: ScrollView
    private lateinit var contenido: LinearLayout

    // Estado
    private lateinit var puntoEstado: View
    private lateinit var tvEstadoTitulo: TextView
    private lateinit var tvEstadoDetalle: TextView
    private lateinit var swActivo: Switch

    // Pasos
    private lateinit var tarjetaPasos: LinearLayout
    private lateinit var tvProgreso: TextView
    private lateinit var contPasos: LinearLayout

    // Conexión
    private lateinit var tarjetaConexion: LinearLayout
    private lateinit var etUrl: EditText
    private lateinit var etKey: EditText
    private lateinit var btnProbar: Button
    private lateinit var btnPanel: Button
    private lateinit var banner: TextView

    // Chats
    private lateinit var tarjetaChats: LinearLayout
    private lateinit var contPermitidos: LinearLayout
    private lateinit var contDetectados: LinearLayout
    private lateinit var tvDetectadosAyuda: TextView
    private lateinit var etNuevo: EditText
    private lateinit var swGrupos: Switch

    // Actividad
    private lateinit var contLog: LinearLayout
    private lateinit var btnLimpiar: Button

    private var firmaPasos = ""
    private var firmaPermitidos = ""
    private var firmaDetectados = ""
    private var firmaLog = ""

    private val ciclo = object : Runnable {
        override fun run() {
            refrescar()
            handler.postDelayed(this, 3000)
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

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
        firmaPasos = ""; firmaPermitidos = ""; firmaDetectados = ""; firmaLog = ""
        handler.post(ciclo)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ciclo)
    }

    /** hannmat://config?url=...&key=...  -> deja la app configurada con un toque desde el panel. */
    private fun aplicarEnlace(i: Intent?) {
        val u: Uri = i?.data ?: return
        if (u.scheme != "hannmat") return
        u.getQueryParameter("url")?.let { prefs.url = Servidor.normalizarUrl(it); etUrl.setText(prefs.url) }
        u.getQueryParameter("key")?.let { prefs.key = it; etKey.setText(prefs.key) }
        Toast.makeText(this, "Listo: la app quedó configurada con tu servidor", Toast.LENGTH_LONG).show()
        refrescar()
        probarConexion()
    }

    // ------------------------------------------------------------------ utilidades de diseño

    private fun c(id: Int): Int = resources.getColor(id, theme)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun forma(color: Int, radio: Int, borde: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radio).toFloat()
        if (borde != null) setStroke(dp(1), borde)
    }

    private fun txt(t: CharSequence, tam: Float, color: Int, negrita: Boolean = false): TextView = TextView(this).apply {
        text = t
        textSize = tam
        setTextColor(color)
        if (negrita) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(w: Int, h: Int, arriba: Int = 0, abajo: Int = 0, peso: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, peso).apply { topMargin = dp(arriba); bottomMargin = dp(abajo) }

    private val llena = ViewGroup.LayoutParams.MATCH_PARENT
    private val ajusta = ViewGroup.LayoutParams.WRAP_CONTENT

    private val PRIMARIO = 0
    private val SECUNDARIO = 1
    private val TEXTO = 2

    private fun boton(t: String, tipo: Int, chico: Boolean = false, alTocar: () -> Unit): Button = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = if (chico) 13f else 15f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minHeight = dp(if (chico) 36 else 48)
        minimumHeight = dp(if (chico) 36 else 48)
        setPadding(dp(if (chico) 12 else 18), 0, dp(if (chico) 12 else 18), 0)
        val fondo: Int
        val letra: Int
        when (tipo) {
            PRIMARIO -> { fondo = c(R.color.marca); letra = c(R.color.sobre_marca) }
            SECUNDARIO -> { fondo = c(R.color.marca_suave); letra = c(R.color.marca) }
            else -> { fondo = Color.TRANSPARENT; letra = c(R.color.marca) }
        }
        setTextColor(letra)
        background = RippleDrawable(ColorStateList.valueOf(0x33000000), forma(fondo, 12), null)
        setOnClickListener { alTocar() }
    }

    private fun campo(pista: String, valor: String): EditText = EditText(this).apply {
        setText(valor)
        hint = pista
        textSize = 15f
        setTextColor(c(R.color.texto))
        setHintTextColor(c(R.color.texto_suave))
        background = forma(c(R.color.campo), 12, c(R.color.borde))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setSingleLine(true)
    }

    private fun alCambiar(et: EditText, f: (String) -> Unit) {
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
        })
    }

    private fun tarjeta(titulo: String, ayuda: String? = null): LinearLayout {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = forma(c(R.color.tarjeta), 18, c(R.color.borde))
        }
        t.addView(txt(titulo, 17f, c(R.color.texto), true))
        if (ayuda != null) t.addView(txt(ayuda, 13.5f, c(R.color.texto_suave)), lp(llena, ajusta, 4))
        return t
    }

    private fun etiqueta(t: String) = txt(t, 13f, c(R.color.texto_suave), true).apply { setPadding(dp(2), 0, 0, dp(6)) }

    // ------------------------------------------------------------------ construcción de la pantalla

    private fun construirPantalla() {
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(c(R.color.fondo)) }
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(raiz, ViewGroup.LayoutParams(llena, ajusta))
        setContentView(scroll)

        raiz.addView(construirEncabezado(), lp(llena, ajusta))

        contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }
        raiz.addView(contenido, lp(llena, ajusta))

        // 1) Pasos
        tarjetaPasos = tarjeta("Configuración inicial")
        tvProgreso = txt("", 13.5f, c(R.color.texto_suave))
        tarjetaPasos.addView(tvProgreso, lp(llena, ajusta, 4, 6))
        contPasos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tarjetaPasos.addView(contPasos, lp(llena, ajusta))
        contenido.addView(tarjetaPasos, lp(llena, ajusta, 0, 14))

        // 2) Conexión
        tarjetaConexion = tarjeta("Conexión con tu servidor", "Estos dos datos salen del panel de tu sitio, en Admin → App WhatsApp.")
        val tip = txt(
            "Más fácil: abre el panel desde este celular y toca el botón verde «Configurar la app automáticamente». Se llenan solos.",
            13f, c(R.color.marca)
        ).apply {
            background = forma(c(R.color.marca_suave), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        tarjetaConexion.addView(tip, lp(llena, ajusta, 12, 14))
        tarjetaConexion.addView(etiqueta("Dirección del servidor"))
        etUrl = campo("https://tusitio.com/api/bot.php?i=1", prefs.url).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        tarjetaConexion.addView(etUrl, lp(llena, ajusta, 0, 12))
        tarjetaConexion.addView(etiqueta("Clave de la app"))
        val filaClave = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        etKey = campo("Cópiala del panel", prefs.key).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        filaClave.addView(etKey, lp(0, ajusta, peso = 1f))
        val btnVer = boton("Ver", TEXTO, true) {}
        btnVer.setOnClickListener {
            val oculta = (etKey.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD
            etKey.inputType = InputType.TYPE_CLASS_TEXT or
                (if (oculta) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            etKey.setSelection(etKey.text.length)
            btnVer.text = if (oculta) "Ocultar" else "Ver"
        }
        filaClave.addView(btnVer, lp(ajusta, ajusta))
        tarjetaConexion.addView(filaClave, lp(llena, ajusta, 0, 14))
        alCambiar(etUrl) { prefs.url = it; ocultarBanner(); refrescar() }
        alCambiar(etKey) { prefs.key = it; ocultarBanner(); refrescar() }

        btnProbar = boton("Probar conexión", PRIMARIO) { probarConexion() }
        tarjetaConexion.addView(btnProbar, lp(llena, ajusta))
        banner = TextView(this).apply {
            textSize = 14f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        tarjetaConexion.addView(banner, lp(llena, ajusta, 12))
        btnPanel = boton("Abrir el panel de mi sitio", SECUNDARIO) {
            val panel = Servidor.urlPanel(prefs.url)
            if (panel == null) Toast.makeText(this, "Primero escribe la dirección del servidor", Toast.LENGTH_SHORT).show()
            else abrir(Intent(Intent.ACTION_VIEW, Uri.parse(panel)), "No se pudo abrir el navegador")
        }
        tarjetaConexion.addView(btnPanel, lp(llena, ajusta, 10))
        contenido.addView(tarjetaConexion, lp(llena, ajusta, 0, 14))

        // 3) A quién contesta
        tarjetaChats = tarjeta("A quién contesta", "Hannmat solo responde a los contactos y grupos de esta lista. A nadie más.")
        contPermitidos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tarjetaChats.addView(contPermitidos, lp(llena, ajusta, 12))
        tarjetaChats.addView(etiqueta("Agregar un chat"), lp(llena, ajusta, 12))
        val filaNuevo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        etNuevo = campo("Nombre tal como sale en WhatsApp", "").apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, accion, _ ->
                if (accion == EditorInfo.IME_ACTION_DONE) { agregarDesdeCampo(); true } else false
            }
        }
        filaNuevo.addView(etNuevo, lp(0, ajusta, peso = 1f))
        filaNuevo.addView(boton("Agregar", SECUNDARIO, true) { agregarDesdeCampo() }, lp(ajusta, ajusta).apply { leftMargin = dp(8) })
        tarjetaChats.addView(filaNuevo, lp(llena, ajusta))

        tarjetaChats.addView(txt("Chats detectados", 14.5f, c(R.color.texto), true), lp(llena, ajusta, 18))
        tvDetectadosAyuda = txt("", 13f, c(R.color.texto_suave))
        tarjetaChats.addView(tvDetectadosAyuda, lp(llena, ajusta, 2, 6))
        contDetectados = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tarjetaChats.addView(contDetectados, lp(llena, ajusta))

        val filaGrupos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val colGrupos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colGrupos.addView(txt("En grupos, contestar solo si empiezan con «Hannmat»", 14.5f, c(R.color.texto), true))
        colGrupos.addView(txt("Así no contesta todos los mensajes del grupo, solo cuando lo llaman.", 12.5f, c(R.color.texto_suave)), lp(llena, ajusta, 2))
        filaGrupos.addView(colGrupos, lp(0, ajusta, peso = 1f))
        swGrupos = Switch(this).apply {
            isChecked = prefs.groupTrigger
            setOnCheckedChangeListener { _, marcado -> prefs.groupTrigger = marcado }
        }
        filaGrupos.addView(swGrupos, lp(ajusta, ajusta).apply { leftMargin = dp(10) })
        tarjetaChats.addView(filaGrupos, lp(llena, ajusta, 18))

        val filaDesconocidos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val colDesconocidos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colDesconocidos.addView(txt("Contestar también a números desconocidos", 14.5f, c(R.color.texto), true))
        colDesconocidos.addView(txt("Para candidatos que te escriben y no están guardados en tus contactos (el chat aparece como número). No aplica a grupos.", 12.5f, c(R.color.texto_suave)), lp(llena, ajusta, 2))
        filaDesconocidos.addView(colDesconocidos, lp(0, ajusta, peso = 1f))
        val swDesconocidos = Switch(this).apply {
            isChecked = prefs.contestarDesconocidos
            setOnCheckedChangeListener { _, marcado -> prefs.contestarDesconocidos = marcado }
        }
        filaDesconocidos.addView(swDesconocidos, lp(ajusta, ajusta).apply { leftMargin = dp(10) })
        tarjetaChats.addView(filaDesconocidos, lp(llena, ajusta, 18))
        contenido.addView(tarjetaChats, lp(llena, ajusta, 0, 14))

        // 4) Actividad
        val tarjetaLog = tarjeta("Actividad reciente", "Lo último que Hannmat hizo o los errores que encontró.")
        contLog = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tarjetaLog.addView(contLog, lp(llena, ajusta, 10))
        btnLimpiar = boton("Limpiar actividad", TEXTO, true) { prefs.limpiarLog(); firmaLog = ""; refrescar() }
        tarjetaLog.addView(btnLimpiar, lp(ajusta, ajusta, 6))
        contenido.addView(tarjetaLog, lp(llena, ajusta, 0, 14))

        // 5) Ayuda
        val ayuda = tarjeta("¿Cómo funciona?")
        val pasos = listOf(
            "Alguien de tu lista te escribe por WhatsApp.",
            "Hannmat lee la notificación y consulta tus vacantes en vivo en tu servidor.",
            "Contesta por ti desde la misma notificación."
        )
        pasos.forEachIndexed { i, p ->
            ayuda.addView(txt("${i + 1}.  $p", 14f, c(R.color.texto_suave)), lp(llena, ajusta, if (i == 0) 10 else 6))
        }
        ayuda.addView(
            txt("El celular debe estar prendido, con internet y con WhatsApp instalado. Recomendado: usa un número de WhatsApp secundario para esto.",
                13f, c(R.color.alerta)).apply {
                background = forma(c(R.color.alerta_suave), 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }, lp(llena, ajusta, 12)
        )
        contenido.addView(ayuda, lp(llena, ajusta, 0, 10))

        contenido.addView(txt("Hannmat · versión ${versionApp()}", 12f, c(R.color.texto_suave)).apply { gravity = Gravity.CENTER }, lp(llena, ajusta, 6))
    }

    private fun construirEncabezado(): View {
        val enc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c(R.color.marca_oscuro), c(R.color.marca))).apply {
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        val marca = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icono = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
        }
        marca.addView(icono, lp(dp(46), dp(46)).apply { rightMargin = dp(12) })
        val nombres = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nombres.addView(txt("Hannmat", 24f, c(R.color.sobre_marca), true))
        nombres.addView(txt("Asistente de WhatsApp para reclutadores", 13f, c(R.color.sobre_marca)).apply { alpha = 0.88f })
        marca.addView(nombres, lp(0, ajusta, peso = 1f))
        enc.addView(marca, lp(llena, ajusta))

        // Tarjeta de estado con el interruptor principal
        val estado = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            background = forma(c(R.color.tarjeta), 18)
        }
        puntoEstado = View(this)
        estado.addView(puntoEstado, lp(dp(12), dp(12)).apply { rightMargin = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvEstadoTitulo = txt("", 16.5f, c(R.color.texto), true)
        tvEstadoDetalle = txt("", 13f, c(R.color.texto_suave))
        col.addView(tvEstadoTitulo)
        col.addView(tvEstadoDetalle, lp(llena, ajusta, 2))
        estado.addView(col, lp(0, ajusta, peso = 1f))
        swActivo = Switch(this)
        swActivo.setOnCheckedChangeListener { _, marcado ->
            if (ignorarSwitch) return@setOnCheckedChangeListener
            if (marcado && !listoParaEncender()) {
                Toast.makeText(this, "Primero completa los pasos de configuración", Toast.LENGTH_LONG).show()
                ignorarSwitch = true
                swActivo.isChecked = false
                ignorarSwitch = false
                irA(tarjetaPasos)
            } else {
                prefs.enabled = marcado
            }
            refrescar()
        }
        estado.addView(swActivo, lp(ajusta, ajusta))
        enc.addView(estado, lp(llena, ajusta, 18))
        return enc
    }

    // ------------------------------------------------------------------ lógica

    private fun permisoConcedido(): Boolean {
        val lista = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return lista.contains(packageName)
    }

    private fun sinAhorroDeBateria(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun listoParaEncender(): Boolean =
        prefs.url.isNotBlank() && prefs.key.isNotBlank() && permisoConcedido() && prefs.allowed.isNotEmpty()

    private fun versionApp(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun abrir(intent: Intent, avisoSiFalla: String) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, avisoSiFalla, Toast.LENGTH_LONG).show()
        }
    }

    private fun irA(vista: View) {
        scroll.post { scroll.smoothScrollTo(0, vista.top + (vista.parent as View).top - dp(8)) }
    }

    private fun ocultarBanner() {
        if (::banner.isInitialized) banner.visibility = View.GONE
    }

    private fun mostrarBanner(ok: Boolean, mensaje: String) {
        banner.text = mensaje
        banner.setTextColor(c(if (ok) R.color.ok else R.color.error))
        banner.background = forma(c(if (ok) R.color.ok_suave else R.color.error_suave), 12)
        banner.visibility = View.VISIBLE
    }

    private fun probarConexion() {
        if (probando) return
        // Deja la dirección completa (https:// y ?i=1) antes de probar
        val normal = Servidor.normalizarUrl(etUrl.text.toString())
        if (normal != etUrl.text.toString().trim()) etUrl.setText(normal)
        prefs.url = normal
        prefs.key = etKey.text.toString()
        if (prefs.url.isBlank() || prefs.key.isBlank()) {
            mostrarBanner(false, "Escribe la dirección del servidor y la clave, o usa el botón verde del panel.")
            return
        }
        probando = true
        btnProbar.isEnabled = false
        btnProbar.text = "Probando…"
        ocultarBanner()
        thread {
            val r = Servidor.probar(applicationContext, prefs)
            runOnUiThread {
                probando = false
                if (isFinishing) return@runOnUiThread
                btnProbar.isEnabled = true
                btnProbar.text = "Probar conexión"
                val resumen = r.texto.lines().firstOrNull { it.isNotBlank() }?.take(140) ?: ""
                mostrarBanner(r.ok, if (r.ok) "Conexión correcta. Hannmat contestó: «$resumen»" else r.texto)
                refrescar()
            }
        }
    }

    private fun agregarChat(nombre: String) {
        val n = nombre.trim()
        if (n.isEmpty()) return
        val actuales = prefs.allowed
        if (actuales.any { Prefs.norm(it) == Prefs.norm(n) }) {
            Toast.makeText(this, "Ese chat ya está en la lista", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.allowed = actuales + n
        refrescar()
    }

    private fun agregarDesdeCampo() {
        agregarChat(etNuevo.text.toString())
        etNuevo.setText("")
    }

    private fun quitarChat(nombre: String) {
        prefs.allowed = prefs.allowed.filter { it != nombre }
        refrescar()
    }

    // ------------------------------------------------------------------ pintar el estado

    private fun refrescar() {
        if (!::swActivo.isInitialized || !::contLog.isInitialized) return
        val permiso = permisoConcedido()
        val bateria = sinAhorroDeBateria()
        val permitidos = prefs.allowed
        val listo = listoParaEncender()

        // --- Estado general
        val dot: Int
        when {
            !listo -> {
                tvEstadoTitulo.text = "Falta configurar"
                tvEstadoDetalle.text = "Completa los pasos de abajo para encenderlo"
                dot = c(R.color.alerta)
            }
            prefs.enabled -> {
                tvEstadoTitulo.text = "Hannmat está activo"
                tvEstadoDetalle.text = "Contestando a ${permitidos.size} ${if (permitidos.size == 1) "chat" else "chats"}"
                dot = c(R.color.ok)
            }
            else -> {
                tvEstadoTitulo.text = "Hannmat está apagado"
                tvEstadoDetalle.text = "Enciéndelo para que conteste en WhatsApp"
                dot = c(R.color.texto_suave)
            }
        }
        puntoEstado.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) }
        if (swActivo.isChecked != prefs.enabled) {
            ignorarSwitch = true
            swActivo.isChecked = prefs.enabled
            ignorarSwitch = false
        }

        // --- Pasos
        val firmaP = "$permiso|$bateria|${prefs.conexionOk}|${prefs.url.isNotBlank()}|${prefs.key.isNotBlank()}|${permitidos.size}"
        if (firmaP != firmaPasos) {
            firmaPasos = firmaP
            contPasos.removeAllViews()
            val datosListos = prefs.url.isNotBlank() && prefs.key.isNotBlank()
            val hechos = listOf(datosListos && prefs.conexionOk, permiso, bateria, permitidos.isNotEmpty())
            contPasos.addView(paso(1, hechos[0], "Conectar con tu servidor",
                if (hechos[0]) "Conexión probada" else if (datosListos) "Toca «Probar conexión» para comprobarla" else "Escribe la dirección y la clave",
                if (hechos[0]) null else "Ir") { irA(tarjetaConexion) })
            contPasos.addView(paso(2, hechos[1], "Permitir leer las notificaciones",
                if (hechos[1]) "Permiso concedido" else "Así Hannmat se entera de los mensajes de WhatsApp",
                if (hechos[1]) null else "Dar permiso") {
                abrir(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "No se pudo abrir los ajustes")
            })
            contPasos.addView(paso(3, hechos[2], "Quitar el ahorro de batería",
                if (hechos[2]) "Listo, no se va a dormir" else "Recomendado: evita que Android la apague",
                if (hechos[2]) null else "Quitar") {
                abrir(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), "No se pudo abrir los ajustes")
            })
            contPasos.addView(paso(4, hechos[3], "Elegir a quién contesta",
                if (hechos[3]) "${permitidos.size} ${if (permitidos.size == 1) "chat elegido" else "chats elegidos"}" else "Agrega al menos un contacto o grupo",
                if (hechos[3]) null else "Elegir") { irA(tarjetaChats) })
            val n = hechos.count { it }
            tvProgreso.text = if (n == 4) "Todo listo. Ya puedes encender Hannmat." else "$n de 4 pasos completados"
            tvProgreso.setTextColor(c(if (n == 4) R.color.ok else R.color.texto_suave))
        }
        btnPanel.visibility = if (Servidor.urlPanel(prefs.url) != null) View.VISIBLE else View.GONE

        // --- Lista de chats permitidos
        val firmaA = permitidos.joinToString("\n")
        if (firmaA != firmaPermitidos) {
            firmaPermitidos = firmaA
            contPermitidos.removeAllViews()
            if (permitidos.isEmpty()) {
                contPermitidos.addView(
                    txt("Todavía no hay chats. Escribe un nombre abajo o toca uno de los detectados.", 13.5f, c(R.color.alerta)).apply {
                        background = forma(c(R.color.alerta_suave), 12)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    }, lp(llena, ajusta)
                )
            }
            permitidos.forEach { nombre ->
                contPermitidos.addView(filaChat(nombre, "Quitar", true) { quitarChat(nombre) }, lp(llena, ajusta, 0, 6))
            }
        }

        // --- Chats detectados
        val normPerm = permitidos.map { Prefs.norm(it) }
        val porAgregar = prefs.recent.filter { Prefs.norm(it) !in normPerm }.take(8)
        val firmaD = porAgregar.joinToString("\n")
        if (firmaD != firmaDetectados) {
            firmaDetectados = firmaD
            contDetectados.removeAllViews()
            tvDetectadosAyuda.text = if (porAgregar.isEmpty())
                "Aquí aparecen los chats que te escriben mientras la app está funcionando. Pídele a alguien que te mande un mensaje."
            else "Estos chats te escribieron. Toca «Agregar» para que Hannmat les conteste."
            porAgregar.forEach { nombre ->
                contDetectados.addView(filaChat(nombre, "Agregar", false) { agregarChat(nombre) }, lp(llena, ajusta, 0, 6))
            }
        }

        // --- Actividad
        val log = prefs.log
        val firmaL = log.joinToString("\n")
        if (firmaL != firmaLog) {
            firmaLog = firmaL
            contLog.removeAllViews()
            if (log.isEmpty()) {
                contLog.addView(txt("Todavía no hay actividad. Cuando Hannmat conteste, lo verás aquí.", 13.5f, c(R.color.texto_suave)))
            }
            log.take(12).forEach { linea ->
                val hora = if (linea.length > 14) linea.substring(0, 14) else ""
                val msg = if (linea.length > 16) linea.substring(16) else linea
                val malo = msg.startsWith("No ") || msg.startsWith("Error") || msg.contains("Servidor:")
                val fila = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                fila.addView(txt(hora, 11.5f, c(R.color.texto_suave)))
                fila.addView(txt(msg, 13.5f, c(if (malo) R.color.error else R.color.texto)), lp(llena, ajusta, 1))
                contLog.addView(fila, lp(llena, ajusta))
            }
        }
        btnLimpiar.visibility = if (log.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun paso(n: Int, hecho: Boolean, titulo: String, detalle: String, accion: String?, alTocar: () -> Unit): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val insignia = TextView(this).apply {
            text = if (hecho) "✓" else n.toString()
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(c(if (hecho) R.color.sobre_marca else R.color.marca))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c(if (hecho) R.color.ok else R.color.marca_suave))
            }
        }
        fila.addView(insignia, lp(dp(30), dp(30)).apply { rightMargin = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(titulo, 15f, c(R.color.texto), true))
        col.addView(txt(detalle, 13f, c(if (hecho) R.color.ok else R.color.texto_suave)), lp(llena, ajusta, 1))
        fila.addView(col, lp(0, ajusta, peso = 1f))
        if (accion != null) fila.addView(boton(accion, SECUNDARIO, true, alTocar), lp(ajusta, ajusta).apply { leftMargin = dp(8) })
        return fila
    }

    private fun filaChat(nombre: String, accion: String, esQuitar: Boolean, alTocar: () -> Unit): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(8), dp(6))
            background = forma(c(R.color.campo), 12, c(R.color.borde))
        }
        fila.addView(txt(nombre, 15f, c(R.color.texto)), lp(0, ajusta, peso = 1f))
        val b = boton(accion, TEXTO, true, alTocar)
        if (esQuitar) b.setTextColor(c(R.color.error))
        fila.addView(b, lp(ajusta, ajusta))
        return fila
    }
}
