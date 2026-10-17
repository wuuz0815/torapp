// =============================================================================
// Datei:    MainActivity.kt
// Zweck:    Hauptoberfläche: Tor öffnen, Gerät wählen/koppeln, Verbindung
//           prüfen, Diagnosemodus und Protokollanzeige
// Autor:    Stefan Weinzetl
// Hinweis:  Bluetooth-Logik läuft in Daemon-Threads, Rückmeldungen an die
//           Oberfläche über den Haupt-Thread (Handler.post).
// =============================================================================
package at.weinzetl.torapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var einstellungen: Einstellungen
    private lateinit var log: Protokoll
    private lateinit var btnTor: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtGeraet: TextView
    private lateinit var txtLog: TextView
    private var standardFarbe: ColorStateList? = null
    private val ui = Handler(Looper.getMainLooper())

    /** Aktion, die nach erteilten Berechtigungen ausgeführt wird. */
    private var nachBerechtigung: (() -> Unit)? = null
    private var kopplungsEmpfaenger: BroadcastReceiver? = null
    private var laufendeSuche: Pair<android.bluetooth.le.BluetoothLeScanner, ScanCallback>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        einstellungen = Einstellungen(this)
        log = Gemeinsam.protokoll(this)
        setContentView(oberflaecheBauen())
        log.beobachter = { text -> ui.post { txtLog.text = text } }
        txtLog.text = log.text()
        log.info("Tor-App ${BuildConfig.VERSION_NAME} gestartet (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        geraetAnzeigen()
        mitBerechtigung(fuerSuche = false) {
            if (einstellungen.geraetAdresse == null) einzigenShellyUebernehmen()
        }
    }

    override fun onDestroy() {
        log.beobachter = null
        kopplungBeenden()
        sucheStoppen()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Oberfläche
    // -------------------------------------------------------------------------
    private fun dp(wert: Int): Int = (wert * resources.displayMetrics.density).toInt()

    private fun oberflaecheBauen(): View {
        val gruen = getColor(R.color.tor_gruen)
        val spalte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        spalte.addView(TextView(this).apply {
            text = "TOR"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        btnTor = Button(this).apply {
            text = "Tor öffnen"
            textSize = 24f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(gruen)
            }
            setOnClickListener { torOeffnenKlick() }
        }
        spalte.addView(btnTor, LinearLayout.LayoutParams(dp(240), dp(240)).apply {
            topMargin = dp(32)
            bottomMargin = dp(16)
        })

        txtStatus = TextView(this).apply {
            text = "Bereit"
            textSize = 18f
            gravity = Gravity.CENTER
            minLines = 2
        }
        standardFarbe = txtStatus.textColors
        spalte.addView(txtStatus, breit())

        txtGeraet = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
        }
        spalte.addView(txtGeraet, breit().apply { topMargin = dp(8) })

        spalte.addView(knopf("Gerät wählen") { mitBerechtigung(fuerSuche = false) { geraetWaehlen() } })
        spalte.addView(knopf("Verbindung prüfen (ohne Schalten)") { verbindungPruefenKlick() })
        spalte.addView(knopf("Gespeichertes Passwort löschen") {
            Zugangsspeicher(this, log).verwerfen()
            status("Passwort gelöscht. Es wird beim nächsten Öffnen erneut abgefragt.", null)
        })

        spalte.addView(CheckBox(this).apply {
            text = "Diagnosemodus (ausführliches Protokoll)"
            isChecked = einstellungen.debug
            setOnCheckedChangeListener { _, an ->
                einstellungen.debug = an
                log.debugModus = an
                log.info("Diagnosemodus ${if (an) "ein" else "aus"}")
            }
        }, breit().apply { topMargin = dp(16) })

        spalte.addView(TextView(this).apply {
            text = "Protokoll"
            typeface = Typeface.DEFAULT_BOLD
        }, breit().apply { topMargin = dp(8) })

        txtLog = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        spalte.addView(txtLog, breit())

        spalte.addView(TextView(this).apply {
            text = "Version ${BuildConfig.VERSION_NAME}"
            textSize = 12f
            gravity = Gravity.CENTER
        }, breit().apply { topMargin = dp(16) })

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(spalte)
        }
    }

    private fun breit() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun knopf(beschriftung: String, aktion: () -> Unit) = Button(this).apply {
        text = beschriftung
        isAllCaps = false
        setOnClickListener { aktion() }
        layoutParams = breit().apply { topMargin = dp(8) }
    }

    /** ok: true = grün, false = rot, null = Standardfarbe */
    private fun status(text: String, ok: Boolean?) {
        txtStatus.text = text
        when (ok) {
            true -> txtStatus.setTextColor(getColor(R.color.status_ok))
            false -> txtStatus.setTextColor(getColor(R.color.status_fehler))
            null -> standardFarbe?.let { txtStatus.setTextColor(it) }
        }
    }

    private fun geraetAnzeigen() {
        val adresse = einstellungen.geraetAdresse
        txtGeraet.text = if (adresse == null) {
            "Noch kein Gerät gewählt"
        } else {
            "Gerät: ${einstellungen.geraetName ?: "Shelly"} ($adresse)"
        }
    }

    // -------------------------------------------------------------------------
    // Tor öffnen / Verbindung prüfen
    // -------------------------------------------------------------------------
    private fun torOeffnenKlick() {
        mitBerechtigung(fuerSuche = false) {
            if (einstellungen.geraetAdresse == null && !einzigenShellyUebernehmen()) {
                status("Bitte zuerst ein Gerät wählen.", false)
                geraetWaehlen()
                return@mitBerechtigung
            }
            val steuerung = Gemeinsam.steuerung(this) ?: return@mitBerechtigung
            btnTor.isEnabled = false
            status("Verbinde …", null)
            hintergrund("tor-oeffnen") {
                val (ok, meldung) = steuerung.torOeffnen { m -> ui.post { status(m, null) } }
                ui.post {
                    status(meldung, ok)
                    btnTor.isEnabled = true
                    if (ok) Gemeinsam.vibrieren(this)
                    if (!ok && passwortNoetig(meldung)) passwortAbfragen(meldung) { torOeffnenKlick() }
                }
            }
        }
    }

    private fun verbindungPruefenKlick() {
        mitBerechtigung(fuerSuche = false) {
            val steuerung = Gemeinsam.steuerung(this)
            if (steuerung == null) {
                status("Bitte zuerst ein Gerät wählen.", false)
                return@mitBerechtigung
            }
            status("Prüfe Verbindung …", null)
            hintergrund("verbindung-pruefen") {
                val (ok, meldung) = steuerung.verbindungPruefen { m -> ui.post { status(m, null) } }
                ui.post {
                    status(meldung, ok)
                    if (!ok && passwortNoetig(meldung)) passwortAbfragen(meldung) { verbindungPruefenKlick() }
                }
            }
        }
    }

    private fun hintergrund(name: String, arbeit: () -> Unit) {
        Thread(arbeit, name).apply { isDaemon = true }.start()
    }

    // -------------------------------------------------------------------------
    // Gerät wählen, suchen und koppeln
    // -------------------------------------------------------------------------
    private fun adapter(): BluetoothAdapter? {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
        if (adapter == null) {
            status("Dieses Handy hat kein Bluetooth.", false)
            return null
        }
        if (!adapter.isEnabled) {
            status("Bitte Bluetooth einschalten.", false)
            return null
        }
        return adapter
    }

    private fun gekoppelteShellys(): List<BluetoothDevice> {
        val adapter = adapter() ?: return emptyList()
        val gekoppelt = adapter.bondedDevices
            .filter { (it.name ?: "").startsWith(Konfig.NAMENS_PRAEFIX, ignoreCase = true) }
            .sortedBy { it.name }
        log.info("Gekoppelte Shelly-Geräte: ${gekoppelt.size}")
        return gekoppelt
    }

    /** Übernimmt den Shelly automatisch, wenn genau einer gekoppelt ist. */
    private fun einzigenShellyUebernehmen(): Boolean {
        val gekoppelt = gekoppelteShellys()
        if (gekoppelt.size != 1) return false
        log.info("Genau ein gekoppelter Shelly, automatische Übernahme")
        geraetUebernehmen(gekoppelt[0].address, gekoppelt[0].name)
        return true
    }

    private fun geraetWaehlen() {
        if (adapter() == null) return
        val gekoppelt = gekoppelteShellys()
        val eintraege = gekoppelt.map { "${it.name}\n${it.address}" } + "Neues Gerät suchen und koppeln …"
        AlertDialog.Builder(this)
            .setTitle("Gerät wählen")
            .setItems(eintraege.toTypedArray()) { _, index ->
                log.info("Auswahl in der Liste: Eintrag ${index + 1} von ${eintraege.size}")
                if (index < gekoppelt.size) {
                    geraetUebernehmen(gekoppelt[index].address, gekoppelt[index].name)
                } else {
                    mitBerechtigung(fuerSuche = true) { sucheStarten() }
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun geraetUebernehmen(adresse: String, name: String?) {
        einstellungen.geraetAdresse = adresse
        einstellungen.geraetName = name
        log.info("Gerät übernommen: ${name ?: "?"} ($adresse)")
        geraetAnzeigen()
        status("Gerät gespeichert. Jetzt „Tor öffnen“ oder „Verbindung prüfen“.", true)
    }

    private fun sucheStarten() {
        val adapter = adapter() ?: return
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status("Bluetooth-Suche nicht verfügbar.", false)
            return
        }
        val gefunden = LinkedHashMap<String, String>()
        val suche = object : ScanCallback() {
            override fun onScanResult(typ: Int, ergebnis: ScanResult) {
                val name = ergebnis.scanRecord?.deviceName ?: ergebnis.device.name ?: return
                if (name.startsWith(Konfig.NAMENS_PRAEFIX, ignoreCase = true)) {
                    gefunden[ergebnis.device.address] = name
                }
            }

            override fun onScanFailed(fehlerCode: Int) {
                log.fehler("Gerätesuche fehlgeschlagen (Code $fehlerCode)")
            }
        }
        log.info("=== Aktion gestartet: Gerätesuche ===")
        status("Suche Shelly-Geräte (8 Sekunden) …", null)
        val einstellung = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, einstellung, suche)
            laufendeSuche = Pair(scanner, suche)
        } catch (e: Exception) {
            log.fehler("Suche nicht startbar: ${e.message}")
            status("Suche nicht möglich: ${e.message}", false)
            return
        }
        ui.postDelayed({
            sucheStoppen()
            log.info("Gefunden: ${gefunden.size} Shelly-Gerät(e)")
            log.info("=== Aktion abgeschlossen: Gerätesuche ===")
            if (isFinishing) return@postDelayed
            if (gefunden.isEmpty()) {
                val zusatz = if (Build.VERSION.SDK_INT <= 30) " Standort am Handy muss eingeschaltet sein." else ""
                status("Kein Shelly gefunden. Näher hingehen und Bluetooth am Shelly prüfen.$zusatz", false)
                return@postDelayed
            }
            val liste = gefunden.entries.toList()
            AlertDialog.Builder(this)
                .setTitle("Shelly koppeln")
                .setItems(liste.map { "${it.value}\n${it.key}" }.toTypedArray()) { _, i ->
                    kopplungVorbereiten(liste[i].key, liste[i].value)
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }, 8000)
    }

    private fun sucheStoppen() {
        val (scanner, suche) = laufendeSuche ?: return
        laufendeSuche = null
        try {
            scanner.stopScan(suche)
        } catch (e: Exception) {
            log.debug("stopScan: ${e.message}")
        }
    }

    private fun kopplungVorbereiten(adresse: String, name: String) {
        val adapter = adapter() ?: return
        val geraet = adapter.getRemoteDevice(adresse)
        if (geraet.bondState == BluetoothDevice.BOND_BONDED) {
            log.info("$name ist bereits gekoppelt")
            geraetUebernehmen(adresse, name)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Kopplung vorbereiten")
            .setMessage(
                "Am Shelly in der Weboberfläche unter Settings → Bluetooth auf „Pair new device“ tippen. " +
                    "Danach hier „Koppeln“ wählen (innerhalb von 3 Minuten).",
            )
            .setPositiveButton("Koppeln") { _, _ -> koppeln(geraet, name) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun koppeln(geraet: BluetoothDevice, name: String) {
        kopplungBeenden()
        log.info("=== Aktion gestartet: Kopplung mit $name ===")
        status("Kopple mit $name …", null)
        val empfaenger = object : BroadcastReceiver() {
            override fun onReceive(k: Context, absicht: Intent) {
                val betroffen: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 34) {
                    absicht.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    absicht.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                }
                if (betroffen?.address != geraet.address) return
                val zustand = absicht.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                log.info("Kopplungszustand: ${BleGattKanal.kopplungText(zustand)}")
                when (zustand) {
                    BluetoothDevice.BOND_BONDED -> {
                        kopplungBeenden()
                        log.info("=== Aktion abgeschlossen: Kopplung erfolgreich ===")
                        geraetUebernehmen(geraet.address, name)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        kopplungBeenden()
                        log.warnung("=== Aktion abgeschlossen: Kopplung fehlgeschlagen ===")
                        status("Kopplung fehlgeschlagen. Ist „Pair new device“ am Shelly aktiv?", false)
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(empfaenger, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(empfaenger, filter)
        }
        kopplungsEmpfaenger = empfaenger
        if (!geraet.createBond()) {
            kopplungBeenden()
            log.fehler("Kopplung konnte nicht gestartet werden")
            status("Kopplung konnte nicht gestartet werden.", false)
        }
    }

    private fun kopplungBeenden() {
        kopplungsEmpfaenger?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                log.debug("unregisterReceiver: ${e.message}")
            }
        }
        kopplungsEmpfaenger = null
    }

    // -------------------------------------------------------------------------
    // Passwortabfrage
    // -------------------------------------------------------------------------
    private fun passwortNoetig(meldung: String) =
        meldung == TorSteuerung.PASSWORT_FEHLT || meldung == TorSteuerung.PASSWORT_FALSCH

    private fun passwortAbfragen(hinweis: String, danach: () -> Unit) {
        if (isFinishing) return
        val feld = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Gerätepasswort"
        }
        val rahmen = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(feld, breit())
        }
        AlertDialog.Builder(this)
            .setTitle("Shelly-Passwort")
            .setMessage("$hinweis\nGespeichert wird nur ein verschlüsselter Hash, nicht das Passwort.")
            .setView(rahmen)
            .setPositiveButton("Übernehmen") { _, _ ->
                val wert = feld.text.toString()
                feld.setText("")
                if (wert.isEmpty()) {
                    status("Kein Passwort eingegeben.", false)
                    return@setPositiveButton
                }
                Zugangsspeicher.einrichtungsPasswort = wert
                log.info("Passwort übernommen (nur Arbeitsspeicher bis zur Anmeldung)")
                danach()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Berechtigungen
    // -------------------------------------------------------------------------
    private fun mitBerechtigung(fuerSuche: Boolean, aktion: () -> Unit) {
        val fehlend = Gemeinsam.fehlendeBerechtigungen(this, fuerSuche)
        if (fehlend.isEmpty()) {
            aktion()
            return
        }
        nachBerechtigung = aktion
        requestPermissions(fehlend.toTypedArray(), ANFRAGE_BERECHTIGUNG)
    }

    override fun onRequestPermissionsResult(code: Int, berechtigungen: Array<out String>, ergebnisse: IntArray) {
        super.onRequestPermissionsResult(code, berechtigungen, ergebnisse)
        if (code != ANFRAGE_BERECHTIGUNG) return
        val aktion = nachBerechtigung
        nachBerechtigung = null
        if (ergebnisse.isNotEmpty() && ergebnisse.all { it == PackageManager.PERMISSION_GRANTED }) {
            log.info("Berechtigungen erteilt")
            aktion?.invoke()
        } else {
            log.warnung("Berechtigungen verweigert: ${berechtigungen.joinToString()}")
            status("Ohne Bluetooth-Berechtigung funktioniert die App nicht.", false)
        }
    }

    companion object {
        private const val ANFRAGE_BERECHTIGUNG = 1
    }
}
