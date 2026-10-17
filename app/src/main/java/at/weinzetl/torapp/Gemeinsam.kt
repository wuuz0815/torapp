// =============================================================================
// Datei:    Gemeinsam.kt
// Zweck:    Gemeinsame Bausteine für App und Widget: Einstellungen,
//           Protokoll-Instanz, Berechtigungen, Vibration
// Autor:    Stefan Weinzetl
// =============================================================================
package at.weinzetl.torapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.File

/** Gespeicherte Auswahl. Enthält keine Geheimnisse (nur Geräteadresse und Name). */
class Einstellungen(kontext: Context) {
    private val prefs = kontext.applicationContext.getSharedPreferences("torapp", Context.MODE_PRIVATE)

    var geraetAdresse: String?
        get() = prefs.getString("geraet_adresse", null)
        set(wert) = prefs.edit().putString("geraet_adresse", wert).apply()

    var geraetName: String?
        get() = prefs.getString("geraet_name", null)
        set(wert) = prefs.edit().putString("geraet_name", wert).apply()

    var debug: Boolean
        get() = prefs.getBoolean("debug", false)
        set(wert) = prefs.edit().putBoolean("debug", wert).apply()
}

object Gemeinsam {
    private const val TAG = "TorApp"

    @Volatile
    private var protokollInstanz: Protokoll? = null

    fun protokoll(kontext: Context): Protokoll {
        protokollInstanz?.let { return it }
        synchronized(this) {
            protokollInstanz?.let { return it }
            val app = kontext.applicationContext
            val neu = Protokoll(
                verzeichnis = File(app.filesDir, "logs"),
                debugModus = Einstellungen(app).debug,
                konsole = { stufe, zeile ->
                    when (stufe) {
                        Stufe.ERROR -> Log.e(TAG, zeile)
                        Stufe.WARNING -> Log.w(TAG, zeile)
                        Stufe.DEBUG -> Log.d(TAG, zeile)
                        Stufe.INFO -> Log.i(TAG, zeile)
                    }
                },
            )
            protokollInstanz = neu
            return neu
        }
    }

    /** Liefert die Steuerung oder null, wenn noch kein Gerät gewählt ist. */
    fun steuerung(kontext: Context): TorSteuerung? {
        val app = kontext.applicationContext
        val adresse = Einstellungen(app).geraetAdresse ?: return null
        val log = protokoll(app)
        return TorSteuerung(log, { BleGattKanal(app, adresse, log) })
    }

    /** Benötigte Laufzeit-Berechtigungen; für die Gerätesuche kommen weitere hinzu. */
    fun benoetigteBerechtigungen(fuerSuche: Boolean): Array<String> = when {
        Build.VERSION.SDK_INT >= 31 && fuerSuche ->
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        Build.VERSION.SDK_INT >= 31 ->
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        fuerSuche ->
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    fun fehlendeBerechtigungen(kontext: Context, fuerSuche: Boolean): List<String> =
        benoetigteBerechtigungen(fuerSuche).filter {
            kontext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    fun vibrieren(kontext: Context) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                kontext.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                kontext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Vibration nicht möglich: ${e.message}")
        }
    }
}
