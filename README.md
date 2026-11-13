# Sensorlab

Android-app som viser sensorane i telefonen i sanntid, laga for bruk i klasserommet.

- Flisvegg med alle sensorar telefonen melder, pluss Wi-Fi-signal, batteritemperatur og GPS
- Detaljskjerm med rullande graf (siste 30 sekund), min/snitt/maks og forklaring
- Lydmålar for timen: trafikklys med justerbar grense og lydalarm som tilval
- Nettverk: Wi-Fi-tilkopling, IP-oppsett, trafikkgraf, mobilnett og skanning av nett i nærleiken

Appen brukar ikkje nett og lagrar ingenting.

## Byggja

Krev JDK 17 eller nyare og Android SDK (platform 35).

```bash
./gradlew assembleDebug       # testversjon: app/build/outputs/apk/debug/Sensorlab-x.y-debug.apk
./gradlew assembleRelease     # signert versjon: app/build/outputs/apk/release/Sensorlab-x.y-release.apk
```

Signert release-bygg krev fila `keystore.properties` i rotmappa (ligg **ikkje** i repoet):

```properties
storeFile=/sti/til/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Ved ny versjon: auk `versionCode` (og `versionName`) i `app/build.gradle.kts`, byggj med
`assembleRelease` og legg APK-fila ut som ein release.

Ein telefon som har ein debug-versjon installert, må avinstallera han før den signerte
versjonen kan installerast, fordi dei to er signerte med ulike nøklar.

## Lisens

Copyright © 2026 Frode

Sensorlab er fri programvare under GNU General Public License versjon 3 (GPL-3.0). Sjå `LICENSE`.
