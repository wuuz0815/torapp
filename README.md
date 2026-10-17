# Tor-App (Android)



Öffnet ein Tor über einen Shelly 1 Gen3 per Bluetooth (RPC über BLE, gekoppelte Verbindung).
Impuls: Relais ein, nach 1 Sekunde automatisch aus.

- Version: ausschließlich `APP_VERSION` in `app/build.gradle.kts`
- Build: GitHub Actions (`.github/workflows/apk-bauen.yml`), APK unter Actions → Artifacts
- Release: Tag `vX.Y.Z` passend zu `APP_VERSION` → Release mit APK
- Signatur: Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORT`, `KEY_ALIAS`, `KEY_PASSWORT`
- Protokoll: App-intern unter `files/logs/torapp.log` (5 MB, 5 Sicherungen), Anzeige in der App
