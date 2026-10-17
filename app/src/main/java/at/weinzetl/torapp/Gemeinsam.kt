// =============================================================================
// Datei:    Gemeinsam.kt
// Zweck:    Gemeinsame Bausteine für App und Widget: Einstellungen,
//           Protokoll-Instanz, verschlüsselter Zugang, Berechtigungen, Vibration
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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

/**
 * Verschlüsselung mit einem AES-Schlüssel im Android-Keystore. Der Schlüssel ist
 * nicht exportierbar und verlässt das Gerät nie.
 */
object Tresor {
    private const val ALIAS = "torapp_zugang"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun schluessel(): SecretKey {
        val speicher = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (speicher.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val erzeuger = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        erzeuger.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return erzeuger.generateKey()
    }

    fun verschluesseln(text: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, schluessel())
        val daten = c.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(daten, Base64.NO_WRAP)
    }

    fun entschluesseln(wert: String): String? = try {
        val teile = wert.split(":")
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, Base64.decode(teile[0], Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(teile[1], Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

/**
 * Zugang zum Shelly: speichert nur Realm und HA1 (Hash), verschlüsselt im Tresor.
 * Das Passwort liegt nur bis zur ersten erfolgreichen Anmeldung im Arbeitsspeicher.
 */
class Zugangsspeicher(kontext: Context, private val log: Protokoll) : Zugang {
    private val prefs = kontext.applicationContext.getSharedPreferences("torapp_zugang", Context.MODE_PRIVATE)

    override fun ha1(realm: String): String? {
        val gespeichert = prefs.getString("zugang", null)?.let { Tresor.entschluesseln(it) }
        if (gespeichert != null) {
            val (gRealm, gHa1) = gespeichert.split("\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (gRealm == realm && gHa1.isNotEmpty()) return gHa1
        }
        val passwort = einrichtungsPasswort ?: return null
        einrichtungsPasswort = null
        val ha1 = ShellyDigest.ha1(realm, passwort)
        try {
            prefs.edit().putString("zugang", Tresor.verschluesseln("$realm\n$ha1")).apply()
            log.info("Zugangsdaten verschlüsselt gespeichert (Realm $realm)")
        } catch (e: Exception) {
            log.warnung("Zugangsdaten nicht speicherbar: ${e.message}")
        }
        return ha1
    }

    override fun verwerfen() {
        prefs.edit().remove("zugang").apply()
        einrichtungsPasswort = null
        log.info("Gespeicherte Zugangsdaten gelöscht")
    }

    fun vorhanden(): Boolean = prefs.contains("zugang")

    companion object {
        /** Nur im Arbeitsspeicher, wird nach der ersten Verwendung verworfen. */
        @Volatile
        var einrichtungsPasswort: String? = null
    }
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
        return TorSteuerung(log, { BleGattKanal(app, adresse, log) }, zugang = Zugangsspeicher(app, log))
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
