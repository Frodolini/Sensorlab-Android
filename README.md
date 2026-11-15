# Sensorlab

Android-app som viser sensorane i telefonen i sanntid, laga for bruk i klasserommet.

- Flisvegg med alle sensorar telefonen melder, pluss Wi-Fi-signal, batteritemperatur og GPS
- Detaljskjerm med rullande graf (siste 30 sekund), min/snitt/maks og forklaring
- Lydmålar for timen: trafikklys med justerbar grense og lydalarm som tilval
- Live til klasseskjerm: lydmålaren kan senda lydnivået til ei nettside på storskjermen i klasserommet (tilval, av til vanleg)
- Nettverk: Wi-Fi-tilkopling, IP-oppsett, trafikkgraf, mobilnett og skanning av nett i nærleiken

Appen lagrar ingen målingar. Han brukar berre nett når du slår på «Send til klasseskjermen» i
lydmålaren. Då blir talet for lydnivået sendt ein gong i sekundet. Sjølve lyden forlèt aldri telefonen.

## Live til klasseskjermen

Lydmålaren kan visast på [klasseskjermen](https://frodolini.github.io/klasseskjerm.html), saman med
ei hjelpeliste for elevane.

1. Opne lydmålaren i appen og slå på «Send til klasseskjermen».
2. Første gong spør appen etter sendarnøkkelen. Han blir hugsa på telefonen og ligg ikkje i dette repoet.
3. Lat lydmålaren stå open. Sendinga stoppar når du går ut av lydmålaren eller slår av brytaren.

Meldingane går til databasefunksjonen `lyd_send` i Supabase, som berre slepp gjennom meldingar med
rett sendarnøkkel og sender dei vidare til klasseskjermen på ein privat Realtime-kanal.

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
