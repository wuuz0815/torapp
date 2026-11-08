// =============================================================================
// Datei:    BleGattKanal.kt
// Zweck:    Android-Bluetooth-Anbindung (GATT) für den Shelly-RPC-Dienst.
//           Wandelt die asynchronen Android-Rückrufe in blockierende Aufrufe,
//           die aus einem Hintergrund-Thread genutzt werden.
// Autor:    Stefan Weinzetl
// =============================================================================
package at.weinzetl.torapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class BleGattKanal(
    private val kontext: Context,
    private val adresse: String,
    private val log: Protokoll,
    /** Vor dem Verbinden gezielt suchen (frischt den Bluetooth-Gerätespeicher von Android auf). */
    private val vorherSuchen: Boolean = false,
) : GattKanal {

    private sealed class Ereignis {
        data class Verbindung(val status: Int, val verbunden: Boolean) : Ereignis()
        data class Dienste(val status: Int) : Ereignis()
        data class Geschrieben(val status: Int) : Ereignis()
        data class Mtu(val mtu: Int, val status: Int) : Ereignis()
        class Gelesen(val status: Int, val wert: ByteArray) : Ereignis()
    }

    private val ereignisse = LinkedBlockingQueue<Ereignis>()
    /** Aktive Verbindung (null, sobald getrennt). */
    private var gatt: BluetoothGatt? = null

    /** Muss in jedem Fall mit close() freigegeben werden, auch nach einem Abbruch. */
    private var offen: BluetoothGatt? = null
    private val kennungen = HashMap<Kanal, BluetoothGattCharacteristic>()
    private var mtu = 23

    override val maxBlock: Int
        get() = if (grosseBloeckeAus) Konfig.BLOCK_GROESSE else maxOf(Konfig.BLOCK_GROESSE, minOf(mtu - 3, 244))

    private val rueckruf = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, neuerZustand: Int) {
            ereignisse.offer(Ereignis.Verbindung(status, neuerZustand == BluetoothProfile.STATE_CONNECTED))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            ereignisse.offer(Ereignis.Dienste(status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            ereignisse.offer(Ereignis.Geschrieben(status))
        }

        override fun onMtuChanged(g: BluetoothGatt, neueMtu: Int, status: Int) {
            ereignisse.offer(Ereignis.Mtu(neueMtu, status))
        }

        // Bis Android 12: Wert steht in der Kennung
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                ereignisse.offer(Ereignis.Gelesen(status, c.value ?: ByteArray(0)))
            }
        }

        // Ab Android 13: Wert wird direkt übergeben
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, wert: ByteArray, status: Int) {
            ereignisse.offer(Ereignis.Gelesen(status, wert))
        }
    }

    private fun ereignisErwarten(beschreibung: String, zeitlimitMs: Long = Konfig.ZEITLIMIT_MS): Ereignis {
        val ende = System.currentTimeMillis() + zeitlimitMs
        while (true) {
            val rest = ende - System.currentTimeMillis()
            if (rest <= 0) throw GattFehler("Zeitüberschreitung: $beschreibung")
            val e = ereignisse.poll(rest, TimeUnit.MILLISECONDS)
                ?: throw GattFehler("Zeitüberschreitung: $beschreibung")
            // Verspätete MTU-Antwort: übernehmen und weiter warten
            if (e is Ereignis.Mtu && !beschreibung.startsWith("MTU")) {
                if (e.status == BluetoothGatt.GATT_SUCCESS) mtu = e.mtu
                continue
            }
            if (e is Ereignis.Verbindung && !e.verbunden) {
                gatt = null
                throw GattFehler("Verbindung getrennt bei $beschreibung (${statusText(e.status)})", e.status)
            }
            return e
        }
    }

    override fun verbinden() {
        val manager = kontext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            ?: throw GattFehler("Bluetooth nicht verfügbar")
        val adapter = manager.adapter ?: throw GattFehler("Kein Bluetooth vorhanden")
        if (!adapter.isEnabled) throw GattFehler("Bluetooth ist ausgeschaltet")

        val geraet = adapter.getRemoteDevice(adresse)
        if (geraet.bondState != BluetoothDevice.BOND_BONDED) {
            throw GattFehler(
                "Handy ist nicht mit dem Shelly gekoppelt. Bitte unter „Gerät wählen“ neu koppeln.",
                endgueltig = true,
            )
        }
        if (vorherSuchen) {
            val gesehen = geraetSuchen(adapter)
            log.debug(if (gesehen) "Shelly in Reichweite gefunden" else "Shelly bei der Suche nicht gesehen")
        }
        ereignisse.clear()
        log.debug("Verbinde mit $adresse (Kopplung: ${kopplungText(geraet.bondState)})")
        val g = geraet.connectGatt(kontext, false, rueckruf, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattFehler("Verbindung konnte nicht gestartet werden")
        gatt = g
        offen = g

        val verbindung = ereignisErwarten("Verbindungsaufbau")
        if (verbindung !is Ereignis.Verbindung || verbindung.status != BluetoothGatt.GATT_SUCCESS) {
            val status = (verbindung as? Ereignis.Verbindung)?.status ?: -1
            throw GattFehler("Verbindungsaufbau fehlgeschlagen (${statusText(status)})", status)
        }
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (!grosseBloeckeAus && g.requestMtu(Konfig.MTU_WUNSCH)) {
            try {
                val m = ereignisErwarten("MTU-Aushandlung", 1500)
                if (m is Ereignis.Mtu && m.status == BluetoothGatt.GATT_SUCCESS) mtu = m.mtu
            } catch (e: GattFehler) {
                if (gatt == null) throw e
                log.debug("MTU-Aushandlung ohne Antwort, Standard bleibt")
            }
        } else {
            Thread.sleep(Konfig.PAUSE_NACH_VERBINDUNG_MS)
        }
        log.debug("MTU $mtu, Blockgröße $maxBlock")

        if (!g.discoverServices()) throw GattFehler("Dienstsuche konnte nicht gestartet werden")
        val dienste = ereignisErwarten("Dienstsuche")
        if (dienste !is Ereignis.Dienste || dienste.status != BluetoothGatt.GATT_SUCCESS) {
            throw GattFehler("Dienstsuche fehlgeschlagen (${statusText((dienste as? Ereignis.Dienste)?.status ?: -1)})")
        }

        val dienst = g.getService(UUID_DIENST)
            ?: throw GattFehler("Shelly-RPC-Dienst nicht gefunden. Ist 'Bluetooth device control' am Shelly aktiv?")
        kennungen.clear()
        for ((kanal, uuid) in listOf(Kanal.DATEN to UUID_DATEN, Kanal.TX to UUID_TX, Kanal.RX to UUID_RX)) {
            kennungen[kanal] = dienst.getCharacteristic(uuid)
                ?: throw GattFehler("Kennung $kanal im Shelly-Dienst nicht gefunden")
        }
        log.debug("GATT-Verbindung und RPC-Dienst bereit")
    }

    override fun schreiben(kanal: Kanal, daten: ByteArray) {
        var versuch = 1
        while (true) {
            try {
                einmalSchreiben(kanal, daten)
                if (versuch > 1) log.debug("Schreiben auf $kanal im $versuch. Anlauf erfolgreich")
                return
            } catch (e: GattFehler) {
                // Längenangabe darf gefahrlos erneut gesendet werden (überschreibt nur den Zähler).
                // Typisch beim ersten Schreiben, solange die Verschlüsselung noch aufgebaut wird.
                if (kanal == Kanal.TX && gatt != null && versuch < Konfig.TX_WIEDERHOLUNGEN) {
                    log.debug("Schreiben auf TX abgelehnt (${e.message}), neuer Anlauf in derselben Verbindung")
                    Thread.sleep(Konfig.TX_PAUSE_MS * versuch)
                    versuch++
                    continue
                }
                if (kanal == Kanal.DATEN && daten.size > Konfig.BLOCK_GROESSE && !grosseBloeckeAus) {
                    grosseBloeckeAus = true
                    log.warnung("Große Datenblöcke abgelehnt, ab jetzt ${Konfig.BLOCK_GROESSE}-Byte-Blöcke")
                }
                throw e
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun einmalSchreiben(kanal: Kanal, daten: ByteArray) {
        val g = gatt ?: throw GattFehler("Nicht verbunden")
        val c = kennungen[kanal] ?: throw GattFehler("Kennung $kanal fehlt")
        val gestartet = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, daten, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = daten
            g.writeCharacteristic(c)
        }
        if (!gestartet) throw GattFehler("Schreiben auf $kanal konnte nicht gestartet werden")
        val e = ereignisErwarten("Schreiben $kanal")
        if (e !is Ereignis.Geschrieben || e.status != BluetoothGatt.GATT_SUCCESS) {
            val status = (e as? Ereignis.Geschrieben)?.status ?: -1
            throw GattFehler("Schreiben auf $kanal fehlgeschlagen (${statusText(status)})", status)
        }
    }

    override fun lesen(kanal: Kanal): ByteArray {
        val g = gatt ?: throw GattFehler("Nicht verbunden")
        val c = kennungen[kanal] ?: throw GattFehler("Kennung $kanal fehlt")
        if (!g.readCharacteristic(c)) throw GattFehler("Lesen von $kanal konnte nicht gestartet werden")
        val e = ereignisErwarten("Lesen $kanal")
        if (e !is Ereignis.Gelesen || e.status != BluetoothGatt.GATT_SUCCESS) {
            throw GattFehler("Lesen von $kanal fehlgeschlagen (${statusText((e as? Ereignis.Gelesen)?.status ?: -1)})")
        }
        return e.wert
    }

    override fun trennen() {
        // Auch nach einem Verbindungsabbruch freigeben: Sonst bleibt bei Android ein
        // GATT-Client belegt, und weitere Versuche scheitern zunehmend (Status 133).
        val g = offen ?: return
        offen = null
        gatt = null
        try {
            g.disconnect()
        } catch (e: Exception) {
            log.debug("disconnect: ${e.message}")
        }
        try {
            g.close()
        } catch (e: Exception) {
            log.debug("close: ${e.message}")
        }
        log.debug("Bluetooth-Verbindung freigegeben")
    }

    /** Kurze, gezielte Suche nach der Shelly-Adresse (max. 3 s). */
    private fun geraetSuchen(adapter: BluetoothAdapter): Boolean {
        if (!Gemeinsam.sucheErlaubt(kontext)) {
            log.debug("Suche nicht erlaubt (Berechtigung fehlt), verbinde direkt")
            return false
        }
        val scanner = adapter.bluetoothLeScanner ?: return false
        val gefunden = CountDownLatch(1)
        val suche = object : ScanCallback() {
            override fun onScanResult(typ: Int, ergebnis: ScanResult) {
                if (ergebnis.device.address.equals(adresse, ignoreCase = true)) gefunden.countDown()
            }
        }
        return try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(adresse.uppercase()).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                suche,
            )
            gefunden.await(3000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            log.debug("Suche nicht möglich: ${e.message}")
            false
        } finally {
            try {
                scanner.stopScan(suche)
            } catch (e: Exception) {
                log.debug("stopScan: ${e.message}")
            }
        }
    }

    companion object {
        /** Wird gesetzt, wenn der Shelly große Blöcke ablehnt (gilt bis App-Neustart). */
        @Volatile
        var grosseBloeckeAus = false

        val UUID_DIENST: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f53-56435f49445f")
        val UUID_DATEN: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f64-6174615f5f5f")
        val UUID_TX: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f74-785f63746c5f")
        val UUID_RX: UUID = UUID.fromString("5f6d4f53-5f52-5043-5f72-785f63746c5f")

        /** Übersetzt Android-GATT-Statuscodes in verständlichen Text. */
        fun statusText(status: Int): String = when (status) {
            0 -> "Status 0, erfolgreich"
            5 -> "Status 5, Anmeldung fehlt – Kopplung prüfen"
            8 -> "Status 8, Zeitüberschreitung der Funkverbindung"
            14 -> "Status 14, vom Shelly abgelehnt (Verschlüsselung noch nicht bereit)"
            15 -> "Status 15, Verschlüsselung fehlt – Kopplung prüfen"
            19 -> "Status 19, vom Shelly getrennt"
            22 -> "Status 22, vom Handy getrennt"
            62 -> "Status 62, Verbindung nicht aufgebaut"
            133 -> "Status 133, allgemeiner Android-Bluetooth-Fehler"
            137 -> "Status 137, Kopplung abgelehnt – neu koppeln"
            else -> "Status $status"
        }

        fun kopplungText(zustand: Int): String = when (zustand) {
            BluetoothDevice.BOND_BONDED -> "gekoppelt"
            BluetoothDevice.BOND_BONDING -> "wird gekoppelt"
            else -> "nicht gekoppelt"
        }
    }
}
