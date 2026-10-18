// =============================================================================
// Datei:    ShellyRpc.kt
// Zweck:    Shelly-RPC über Bluetooth (Rahmenprotokoll) und Torsteuerung
//           mit Wiederholung und Schutz vor Doppelimpuls
// Autor:    Stefan Weinzetl
// Hinweis:  Reines Kotlin ohne Android-Bezug (lokal testbar). Die Bluetooth-
//           Anbindung liefert BleGattKanal über die Schnittstelle GattKanal.
// =============================================================================
package at.weinzetl.torapp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Die drei GATT-Kennungen des Shelly-RPC-Dienstes. */
enum class Kanal { DATEN, TX, RX }

class GattFehler(nachricht: String, val status: Int = -1) : Exception(nachricht)

/** Abstraktion der Bluetooth-Verbindung (Android: BleGattKanal, Tests: Simulation). */
interface GattKanal {
    /** Größte Blockgröße je Schreibvorgang (abhängig von der ausgehandelten MTU). */
    val maxBlock: Int get() = Konfig.BLOCK_GROESSE
    fun verbinden()
    fun schreiben(kanal: Kanal, daten: ByteArray)
    fun lesen(kanal: Kanal): ByteArray
    fun trennen()
}

object Konfig {
    const val SCHALTER_ID = 0                    // Relais-Kanal des Shelly 1 Gen3
    const val IMPULS_SEKUNDEN = 1                // Relais fällt danach selbst ab
    const val BLOCK_GROESSE = 20                 // Mindest-Blockgröße (Standard-MTU 23)
    const val MTU_WUNSCH = 247                   // größere Pakete = weniger Schreibvorgänge
    const val TX_WIEDERHOLUNGEN = 4              // Längenangabe in derselben Verbindung erneut senden
    const val TX_PAUSE_MS = 150L                 // Wartezeit, bis die Verschlüsselung steht
    const val PAUSE_NACH_LAENGE_MS = 50L         // Pause zwischen Längenangabe und Daten
    const val PAUSE_NACH_VERBINDUNG_MS = 300L    // Android braucht Zeit vor der Dienstsuche
    const val ZEITLIMIT_MS = 8000L               // Zeitlimit je Bluetooth-Schritt
    const val VERSUCHE = 4                       // Wiederholungen bei GATT-Fehlern (z. B. Status 133)
    const val PAUSE_ZWISCHEN_VERSUCHEN_MS = 700L // wächst linear je Versuch
    const val QUELLE = "tor-app-android"         // Absenderkennung im RPC-Aufruf
    const val NAMENS_PRAEFIX = "Shelly"          // Filter für die Geräteauswahl
}

data class RpcAntwort(val roh: String, val fehlerCode: Int?, val fehlerText: String?)

/** Liefert den Anmelde-Hash (HA1) zum Realm des Shelly oder null, wenn kein Passwort hinterlegt ist. */
interface Zugang {
    fun ha1(realm: String): String?
    fun verwerfen()
}

/** Digest-Anmeldung der Shelly-RPC-Schnittstelle (SHA-256), identisch zur Web-App. */
object ShellyDigest {
    const val BENUTZER = "admin"
    private val NONCE = Regex("\"nonce\"\\s*:\\s*(?:\"([^\"]+)\"|(\\d+))")
    private val NC = Regex("\"nc\"\\s*:\\s*\"?(\\d+)")
    private val REALM = Regex("\"realm\"\\s*:\\s*\"([^\"]+)\"")
    private val zufall = SecureRandom()

    /** nonceIstText: Shelly sendet die Nonce je nach Firmware als Zahl oder als Text (Base64). */
    data class Herausforderung(val realm: String, val nonce: String, val nc: String, val nonceIstText: Boolean = false)

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun ha1(realm: String, passwort: String): String = sha256("$BENUTZER:$realm:$passwort")

    /**
     * Liest die Herausforderung aus der 401-Antwort. Tolerant gegenüber Varianten:
     * message als maskierter Text oder als Objekt, Zahlen mit oder ohne Anführungszeichen.
     */
    fun herausforderung(antwort: String?): Herausforderung? {
        val text = (antwort ?: return null)
            .replace("\\u0022", "\"")
            .replace("\\\\\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
        val realm = REALM.find(text)?.groupValues?.get(1) ?: return null
        val treffer = NONCE.find(text) ?: return null
        val alsText = treffer.groupValues[1]
        val nc = NC.find(text)?.groupValues?.get(1) ?: "1"
        return if (alsText.isNotEmpty()) {
            Herausforderung(realm, alsText, nc, nonceIstText = true)
        } else {
            Herausforderung(realm, treffer.groupValues[2], nc)
        }
    }

    fun authJson(h: Herausforderung, ha1: String, cnonce: Long = (zufall.nextInt() and 0x7fffffff).toLong()): String {
        val ha2 = sha256("dummy_method:dummy_uri")
        val antwort = sha256("$ha1:${h.nonce}:${h.nc}:$cnonce:auth:$ha2")
        val nonceJson = if (h.nonceIstText) "\"${h.nonce}\"" else h.nonce
        return """{"realm":"${h.realm}","username":"$BENUTZER","nonce":$nonceJson,"cnonce":$cnonce,"response":"$antwort","algorithm":"SHA-256"}"""
    }
}

class ShellyRpc(
    private val kanal: GattKanal,
    private val log: Protokoll,
    private val warten: (Long) -> Unit = { Thread.sleep(it) },
) {
    /**
     * Ab true kann der Shelly den Befehl ausführen: keine automatische Wiederholung mehr.
     * Wird erst vor dem letzten Datenblock gesetzt, da der Shelly erst nach dem
     * vollständigen Rahmen ausführt.
     */
    var datenBegonnen = false
        private set
    private var naechsteId = 1

    fun aufrufen(methode: String, paramsJson: String, authJson: String? = null): RpcAntwort {
        val auth = if (authJson != null) ""","auth":$authJson""" else ""
        val anfrage = """{"id":${naechsteId++},"src":"${Konfig.QUELLE}","method":"$methode","params":$paramsJson$auth}"""
        senden(anfrage.toByteArray(Charsets.UTF_8))
        return auswerten(empfangen())
    }

    private fun senden(bytes: ByteArray) {
        kanal.schreiben(Kanal.TX, ByteBuffer.allocate(4).putInt(bytes.size).array()) // Big Endian
        warten(Konfig.PAUSE_NACH_LAENGE_MS)
        val block = maxOf(Konfig.BLOCK_GROESSE, kanal.maxBlock)
        var pos = 0
        var bloecke = 0
        while (pos < bytes.size) {
            val ende = minOf(pos + block, bytes.size)
            if (ende == bytes.size) datenBegonnen = true
            kanal.schreiben(Kanal.DATEN, bytes.copyOfRange(pos, ende))
            pos = ende
            bloecke++
        }
        log.debug("Rahmen gesendet (${bytes.size} Byte in $bloecke Block/Blöcken à max. $block)")
    }

    private fun empfangen(): String {
        val start = System.currentTimeMillis()
        var laenge = 0
        while (System.currentTimeMillis() - start < Konfig.ZEITLIMIT_MS) {
            val wert = kanal.lesen(Kanal.RX)
            laenge = if (wert.size >= 4) ByteBuffer.wrap(wert, 0, 4).int else 0
            if (laenge > 0) break
            warten(50)
        }
        if (laenge <= 0) throw GattFehler("Keine Antwort vom Shelly")
        val gesamt = ByteArrayOutputStream()
        while (gesamt.size() < laenge) {
            val teil = kanal.lesen(Kanal.DATEN)
            if (teil.isEmpty()) throw GattFehler("Unvollständige Antwort vom Shelly")
            gesamt.write(teil)
        }
        log.debug("Antwort empfangen (${gesamt.size()} Byte)")
        return String(gesamt.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        private val FEHLER_CODE = Regex("\"error\"\\s*:\\s*\\{[^}]*?\"code\"\\s*:\\s*(-?\\d+)")
        private val FEHLER_TEXT = Regex("\"error\"\\s*:\\s*\\{[^}]*?\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        fun auswerten(roh: String): RpcAntwort {
            val code = FEHLER_CODE.find(roh)?.groupValues?.get(1)?.toIntOrNull()
            val text = FEHLER_TEXT.find(roh)?.groupValues?.get(1)
            return RpcAntwort(roh, code, text)
        }
    }
}

class TorSteuerung(
    private val log: Protokoll,
    private val kanalFabrik: () -> GattKanal,
    private val zugang: Zugang? = null,
    private val warten: (Long) -> Unit = { Thread.sleep(it) },
) {
    private sealed class Lauf {
        data class Ok(val antwort: RpcAntwort) : Lauf()
        data class Fehler(val meldung: String) : Lauf()
    }

    /**
     * Öffnet das Tor per Impuls (Relais ein, nach IMPULS_SEKUNDEN automatisch aus).
     * @return Pair(erfolg, meldung)
     */
    fun torOeffnen(statusMelden: (String) -> Unit = {}): Pair<Boolean, String> {
        if (!laeuft.compareAndSet(false, true)) return Pair(false, "Vorgang läuft bereits")
        log.info("=== Aktion gestartet: Tor öffnen ===")
        try {
            val params = """{"id":${Konfig.SCHALTER_ID},"on":true,"toggle_after":${Konfig.IMPULS_SEKUNDEN}}"""
            return when (val lauf = mitWiederholung(statusMelden, sicherWiederholbar = false) { mitAnmeldung(it, "Switch.Set", params) }) {
                is Lauf.Fehler -> Pair(false, lauf.meldung)
                is Lauf.Ok -> fehlerPruefen(lauf.antwort) ?: run {
                    log.info("Impuls erfolgreich gesendet")
                    Pair(true, "Tor wird geöffnet")
                }
            }
        } finally {
            laeuft.set(false)
            log.info("=== Aktion abgeschlossen: Tor öffnen ===")
        }
    }

    /**
     * Prüft die Verbindung ohne zu schalten (entspricht --check / --dry-run).
     * @return Pair(erfolg, meldung)
     */
    fun verbindungPruefen(statusMelden: (String) -> Unit = {}): Pair<Boolean, String> {
        if (!laeuft.compareAndSet(false, true)) return Pair(false, "Vorgang läuft bereits")
        log.info("=== Aktion gestartet: Verbindung prüfen ===")
        try {
            val params = """{"id":${Konfig.SCHALTER_ID}}"""
            return when (val lauf = mitWiederholung(statusMelden, sicherWiederholbar = true) { mitAnmeldung(it, "Switch.GetStatus", params) }) {
                is Lauf.Fehler -> Pair(false, lauf.meldung)
                is Lauf.Ok -> fehlerPruefen(lauf.antwort) ?: run {
                    val zustand = AUSGANG.find(lauf.antwort.roh)?.groupValues?.get(1)
                    val text = when (zustand) {
                        "true" -> "Verbindung in Ordnung, Relais ist gerade EIN"
                        "false" -> "Verbindung in Ordnung, Relais ist AUS"
                        else -> "Verbindung in Ordnung"
                    }
                    log.info(text)
                    Pair(true, text)
                }
            }
        } finally {
            laeuft.set(false)
            log.info("=== Aktion abgeschlossen: Verbindung prüfen ===")
        }
    }

    /** Ruft auf und meldet sich bei Bedarf in derselben Verbindung per Digest an. */
    private fun mitAnmeldung(rpc: ShellyRpc, methode: String, params: String): RpcAntwort {
        val erste = rpc.aufrufen(methode, params)
        if (erste.fehlerCode != 401) return erste
        log.debug("401-Antwort: ${erste.roh.take(400)}")
        val h = ShellyDigest.herausforderung(erste.roh) ?: run {
            log.warnung("Anmeldeanfrage nicht lesbar, Rohantwort: ${erste.roh.take(400)}")
            return erste.copy(fehlerText = "Anmeldeanfrage des Shelly nicht lesbar")
        }
        log.debug("Shelly verlangt Anmeldung (Realm ${h.realm})")
        val ha1 = zugang?.ha1(h.realm) ?: return erste.copy(fehlerText = PASSWORT_FEHLT)
        val zweite = rpc.aufrufen(methode, params, ShellyDigest.authJson(h, ha1))
        if (zweite.fehlerCode == 401) {
            zugang?.verwerfen()
            return zweite.copy(fehlerText = PASSWORT_FALSCH)
        }
        log.debug("Anmeldung erfolgreich")
        return zweite
    }

    private fun fehlerPruefen(antwort: RpcAntwort): Pair<Boolean, String>? {
        val code = antwort.fehlerCode ?: return null
        if (code == 401) {
            return when (antwort.fehlerText) {
                PASSWORT_FEHLT -> {
                    log.warnung("Shelly verlangt Anmeldung, kein Passwort hinterlegt")
                    Pair(false, PASSWORT_FEHLT)
                }
                PASSWORT_FALSCH -> {
                    log.fehler("Anmeldung abgelehnt, gespeicherte Zugangsdaten verworfen")
                    Pair(false, PASSWORT_FALSCH)
                }
                else -> {
                    log.fehler("Anmeldung fehlgeschlagen: ${antwort.fehlerText}")
                    Pair(false, "Anmeldung fehlgeschlagen: ${antwort.fehlerText}")
                }
            }
        }
        log.fehler("Shelly meldet Fehler $code: ${antwort.fehlerText}")
        return Pair(false, "Shelly meldet Fehler $code: ${antwort.fehlerText ?: "unbekannt"}")
    }

    private fun mitWiederholung(
        statusMelden: (String) -> Unit,
        sicherWiederholbar: Boolean,
        aufruf: (ShellyRpc) -> RpcAntwort,
    ): Lauf {
        var letzterFehler: Exception? = null
        for (versuch in 1..Konfig.VERSUCHE) {
            if (versuch > 1) {
                statusMelden("Verbinde … (Versuch $versuch)")
                warten(Konfig.PAUSE_ZWISCHEN_VERSUCHEN_MS * (versuch - 1))
            } else {
                statusMelden("Verbinde …")
            }
            val kanal = kanalFabrik()
            val rpc = ShellyRpc(kanal, log, warten)
            try {
                kanal.verbinden()
                statusMelden("Sende Befehl …")
                val antwort = aufruf(rpc)
                if (versuch > 1) log.info("Erfolgreich im Versuch $versuch")
                return Lauf.Ok(antwort)
            } catch (e: Exception) {
                letzterFehler = e
                log.warnung("Versuch $versuch/${Konfig.VERSUCHE} fehlgeschlagen: ${e.javaClass.simpleName} ${e.message}")
                if (rpc.datenBegonnen && !sicherWiederholbar) {
                    log.warnung("Befehl war bereits unterwegs, keine Wiederholung (Schutz vor Doppelimpuls)")
                    return Lauf.Fehler("Unklar, ob das Tor reagiert hat. Bitte schauen und bei Bedarf nochmal tippen.")
                }
            } finally {
                try {
                    kanal.trennen()
                } catch (e: Exception) {
                    log.debug("Trennen fehlgeschlagen: ${e.message}")
                }
            }
        }
        log.fehler("Alle ${Konfig.VERSUCHE} Versuche fehlgeschlagen: ${letzterFehler?.message}")
        return Lauf.Fehler("Tor nicht erreicht. Näher hingehen und nochmal tippen.")
    }

    companion object {
        /** Kennungen für die Oberfläche: Passwortabfrage anzeigen. */
        const val PASSWORT_FEHLT = "Bitte einmalig das Gerätepasswort des Shelly eingeben."
        const val PASSWORT_FALSCH = "Passwort falsch. Bitte neu eingeben."

        /** Verhindert parallele Vorgänge (App und Widget gleichzeitig). */
        private val laeuft = AtomicBoolean(false)
        private val AUSGANG = Regex("\"output\"\\s*:\\s*(true|false)")
    }
}
