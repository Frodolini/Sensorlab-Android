# Sensorlab

Android-app som viser sensorane i telefonen i sanntid, laga for bruk i klasserommet.

- Flisvegg med alle sensorar telefonen melder, pluss Wi-Fi-signal, batteritemperatur og GPS
- Detaljskjerm med rullande graf (siste 30 sekund), min/snitt/maks og forklaring
- Lydmålar for timen: trafikklys med justerbar grense og lydalarm som tilval
- Nettverk: Wi-Fi-tilkopling, IP-oppsett, trafikkgraf, mobilnett og skanning av nett i nærleiken

Appen brukar ikkje nett og lagrar ingenting.

## Byggja

Krev JDK 17 eller nyare og Android SDK (platform 35).

    ./gradlew assembleDebug

APK-en hamnar i `app/build/outputs/apk/debug/`.

## Lisens

Copyright © 2026 Frode

Sensorlab er fri programvare under GNU General Public License versjon 3 (GPL-3.0). Sjå `LICENSE`.
