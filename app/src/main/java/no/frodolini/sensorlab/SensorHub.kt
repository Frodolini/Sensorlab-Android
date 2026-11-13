package no.frodolini.sensorlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Kor mange målepunkt grafen hugsar (600 punkt à 50 ms = 30 sekund). */
const val HIST = 600
const val TICK_MS = 50L

const val GROUP_MAIN = 0
const val GROUP_PHONE = 1
const val GROUP_OTHER = 2

/** Éi datakjelde = éi flis på startskjermen. */
class Source(
    val id: String,
    val title: String,
    val unit: String,
    val axes: List<String>,
    val info: String,
    val group: Int,
    val tech: String = "",
    val decimals: Int = 2,
) {
    var latest: FloatArray? = null
    var extra: String = ""
    var paused = false
    private val history = Array(axes.size) { FloatArray(HIST) }
    var count = 0
        private set

    fun push() {
        val v = latest ?: return
        if (paused) return
        val i = count % HIST
        for (a in axes.indices) history[a][i] = v.getOrElse(a) { 0f }
        count++
    }

    fun clear() {
        count = 0
    }

    /** Historikken for éin akse, eldst først. */
    fun series(axis: Int): FloatArray {
        val n = minOf(count, HIST)
        val out = FloatArray(n)
        val start = if (count > HIST) count % HIST else 0
        for (k in 0 until n) out[k] = history[axis][(start + k) % HIST]
        return out
    }
}

private class Meta(
    val title: String,
    val unit: String,
    val axes: List<String>,
    val info: String,
    val decimals: Int = 2,
)

private val XYZ = listOf("x", "y", "z")
private val ONE = listOf("")

/** Kjende sensortypar, i den rekkjefølgja dei skal visast. */
private val KNOWN: Map<Int, Meta> = linkedMapOf(
    Sensor.TYPE_ACCELEROMETER to Meta(
        "Akselerometer", "m/s²", XYZ,
        "Måler akselerasjon i tre retningar, medrekna tyngdekrafta. Ligg telefonen i ro på bordet, viser z-aksen om lag 9,81 m/s². Telefonen brukar sensoren til å rotera skjermen, telja steg og oppdaga fall.",
    ),
    Sensor.TYPE_GYROSCOPE to Meta(
        "Gyroskop", "rad/s", XYZ,
        "Måler kor fort telefonen roterer rundt kvar akse. I ro er alle verdiane nær null. Blir brukt til biletstabilisering, spel og VR.",
    ),
    Sensor.TYPE_MAGNETIC_FIELD to Meta(
        "Magnetometer", "µT", XYZ,
        "Måler magnetfeltet rundt telefonen. Jordmagnetfeltet er på 25–65 µT. Hald ein magnet eller ein høgtalar inntil og sjå verdiane hoppa. Dette er kompasset i telefonen.",
        decimals = 1,
    ),
    Sensor.TYPE_PRESSURE to Meta(
        "Barometer", "hPa", ONE,
        "Måler lufttrykket. Trykket fell om lag 0,12 hPa for kvar meter du går oppover, så sensoren kan merka at du går opp ei trapp. Telefonen brukar det til høgdemåling og raskare GPS-fiks.",
    ),
    Sensor.TYPE_LIGHT to Meta(
        "Lyssensor", "lux", ONE,
        "Måler kor mykje lys som treff framsida av telefonen. Eit godt opplyst klasserom ligg på 300–500 lux, sollys ute på over 10 000. Telefonen brukar sensoren til automatisk lysstyrke.",
        decimals = 0,
    ),
    Sensor.TYPE_PROXIMITY to Meta(
        "Nærleikssensor", "cm", ONE,
        "Merkar om noko er tett inntil skjermen, og gjev berre to verdiar: nær eller fjern. Det er denne som slår av skjermen når du held telefonen mot øyra. På nyare Samsung-telefonar får vanlege appar berre ein forenkla variant som reagerer først når du legg handflata heilt inntil øvre del av skjermen. Den eigentlege sensoren er reservert for Samsung sine eigne appar.",
        decimals = 1,
    ),
    Sensor.TYPE_GRAVITY to Meta(
        "Tyngdekraft", "m/s²", XYZ,
        "Ein utrekna sensor: tyngdekrafta skild ut frå akselerometeret. Viser kva veg som er ned, uansett korleis du rører telefonen.",
    ),
    Sensor.TYPE_LINEAR_ACCELERATION to Meta(
        "Lineær akselerasjon", "m/s²", XYZ,
        "Ein utrekna sensor: akselerometeret med tyngdekrafta trekt frå. Viser berre rørsla du sjølv gjev telefonen, og er null når han ligg i ro.",
    ),
    Sensor.TYPE_ROTATION_VECTOR to Meta(
        "Rotasjonsvektor", "", XYZ,
        "Ein utrekna sensor som slår saman akselerometer, gyroskop og magnetometer til éi retning i rommet. Dette kallast sensorfusjon.",
        decimals = 3,
    ),
    Sensor.TYPE_GAME_ROTATION_VECTOR to Meta(
        "Rotasjonsvektor (spel)", "", XYZ,
        "Som rotasjonsvektoren, men utan magnetometeret. Peikar ikkje mot nord, men blir heller ikkje forstyrra av magnetar.",
        decimals = 3,
    ),
    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR to Meta(
        "Rotasjonsvektor (magnetisk)", "", XYZ,
        "Som rotasjonsvektoren, men utan gyroskopet. Brukar mindre straum, men er meir uroleg.",
        decimals = 3,
    ),
    Sensor.TYPE_AMBIENT_TEMPERATURE to Meta(
        "Romtemperatur", "°C", ONE,
        "Måler temperaturen i lufta rundt telefonen. Dei fleste telefonar har ikkje denne sensoren.",
        decimals = 1,
    ),
    Sensor.TYPE_RELATIVE_HUMIDITY to Meta(
        "Luftfukt", "%", ONE,
        "Måler relativ luftfukt. Dei fleste telefonar har ikkje denne sensoren.",
        decimals = 0,
    ),
)

/** Typar som treng eigne løyve eller berre gjev enkelthendingar. */
private val SKIP = setOf(
    Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR,
    Sensor.TYPE_HEART_RATE, Sensor.TYPE_HEART_BEAT,
)

class SensorHub(private val ctx: Context) : SensorEventListener, LocationListener {
    private val sensorManager = ctx.getSystemService(SensorManager::class.java)
    private val bySensor = HashMap<Sensor, Source>()

    val sources: List<Source>
    val wifi = Source(
        "wifi", "Wi-Fi-signal", "dBm", ONE,
        "Signalstyrken (RSSI) på nettet telefonen er kopla til. Talet er negativt: −40 dBm er svært godt, −67 dBm er greitt for video, og under −80 dBm er dårleg. Gå rundt i rommet og sjå korleis veggar og avstand påverkar signalet.",
        GROUP_PHONE, decimals = 0,
    )
    val battery = Source(
        "battery", "Batteritemperatur", "°C", ONE,
        "Temperaturen inne i batteriet. Dette er ikkje romtemperaturen: batteriet blir varmt av lading og tungt arbeid. Telefonen brukar målinga til å verna batteriet.",
        GROUP_PHONE, decimals = 1,
    )
    val gps = Source(
        "gps", "GPS", "m", listOf("høgde", "fart m/s", "nøyaktigheit"),
        "Posisjon frå satellittar. Høgda er meter over ellipsoiden (ikkje heilt det same som meter over havet), og nøyaktigheita er radiusen telefonen meiner han er innanfor. Inne er signalet svakt, så prøv ved eit vindauge.",
        GROUP_PHONE, decimals = 1,
    )

    /** Aukar 20 gonger i sekundet. Composables les denne for å teikna på nytt. */
    var frame by mutableIntStateOf(0)
        private set

    /** Aukar 4 gonger i sekundet, for flisveggen. */
    var slowFrame by mutableIntStateOf(0)
        private set
    var gpsGranted by mutableStateOf(hasLocationPermission())
        private set

    /** Kor mange av «andre sensorar» som faktisk har levert data. */
    var otherLive by mutableIntStateOf(0)
        private set
    private var proximity: Source? = null
    private var proximityFar = 5f

    private var running = false
    private var gpsRunning = false
    private var ticks = 0

    init {
        val list = ArrayList<Source>()
        val seen = HashSet<Int>()
        val all = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        // Kjende typar først, i fast rekkjefølgje.
        for ((type, meta) in KNOWN) {
            val s = sensorManager?.getDefaultSensor(type) ?: continue
            seen += type
            val src = Source("s$type", meta.title, meta.unit, meta.axes, meta.info, GROUP_MAIN, techOf(s), meta.decimals)
            list += src
            bySensor[s] = src
            if (type == Sensor.TYPE_PROXIMITY) {
                proximity = src
                proximityFar = s.maximumRange
            }
        }
        list += wifi
        list += battery
        list += gps
        // Resten: det telefonen elles melder at han har.
        for (s in all) {
            if (s.type in seen || s.type in SKIP) continue
            val mode = s.reportingMode
            if (mode != Sensor.REPORTING_MODE_CONTINUOUS && mode != Sensor.REPORTING_MODE_ON_CHANGE) continue
            val def = sensorManager?.getDefaultSensor(s.type) ?: s
            if (def != s) continue
            seen += s.type
            val src = Source(
                "s${s.type}", s.name, "", listOf("1", "2", "3"),
                "Denne sensoren har appen inga forklaring på. Namnet og tala kjem rett frå telefonen. Produsentane legg ofte inn eigne sensorar som berre deira eigne appar brukar.",
                GROUP_OTHER, techOf(s),
            )
            list += src
            bySensor[s] = src
        }
        sources = list
    }

    private fun techOf(s: Sensor): String = buildString {
        append("Namn: ${s.name}\n")
        append("Produsent: ${s.vendor}\n")
        append("Måleområde: ±${fmt(s.maximumRange, 1)}\n")
        append("Oppløysing: ${String.format(Locale.ROOT, "%.6f", s.resolution).trimEnd('0').trimEnd('.')}\n")
        append("Straumbruk: ${fmt(s.power, 3)} mA\n")
        append("Type-ID: ${s.type} (${s.stringType})")
    }

    fun start() {
        if (running) return
        running = true
        for (s in bySensor.keys) sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        startGps()
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(this)
        if (gpsRunning) {
            ctx.getSystemService(LocationManager::class.java)?.removeUpdates(this)
            gpsRunning = false
        }
    }

    private fun hasLocationPermission() =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Blir kalla ved start og når brukaren har svart på løyvespørsmålet. */
    @SuppressLint("MissingPermission")
    fun startGps() {
        gpsGranted = hasLocationPermission()
        if (!gpsGranted || gpsRunning || !running) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        try {
            if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            gpsRunning = true
        } catch (_: Exception) {
            gps.extra = "GPS er ikkje tilgjengeleg"
        }
    }

    /** Køyrer 20 gonger i sekundet på hovudtråden. */
    fun tick() {
        ticks++
        if (ticks % 20 == 1) pollPhone()
        for (s in sources) s.push()
        if (ticks % 5 == 0) {
            val live = sources.count { it.group == GROUP_OTHER && it.latest != null }
            if (live != otherLive) otherLive = live
        }
        frame++
        if (ticks % 5 == 0) slowFrame++
    }

    private fun pollPhone() {
        try {
            val wm = ctx.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION") val ci = wm?.connectionInfo
            if (ci != null && ci.rssi > -127 && ci.rssi < 0) {
                wifi.latest = floatArrayOf(ci.rssi.toFloat())
                val band = when {
                    ci.frequency >= 5925 -> "6 GHz"
                    ci.frequency >= 4900 -> "5 GHz"
                    else -> "2,4 GHz"
                }
                wifi.extra = "${ci.frequency} MHz ($band) · ${ci.linkSpeed} Mbit/s"
            } else {
                wifi.latest = null
                wifi.extra = "Ikkje kopla til Wi-Fi"
            }
        } catch (_: Exception) {
            wifi.extra = "Kan ikkje lesa Wi-Fi"
        }
        try {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (t != Int.MIN_VALUE) {
                battery.latest = floatArrayOf(t / 10f)
                val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                battery.extra = "${fmt(mv / 1000f, 2)} V · $level %"
            }
        } catch (_: Exception) {
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val src = bySensor[e.sensor] ?: return
        val n = minOf(e.values.size, src.axes.size)
        val cur = src.latest
        val out = if (cur != null && cur.size == src.axes.size) cur else FloatArray(src.axes.size)
        for (i in 0 until n) out[i] = e.values[i]
        src.latest = out
        if (src === proximity) src.extra = if (out[0] >= proximityFar) "Fjern" else "Nær"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(l: Location) {
        gps.latest = floatArrayOf(
            if (l.hasAltitude()) l.altitude.toFloat() else 0f,
            if (l.hasSpeed()) l.speed else 0f,
            if (l.hasAccuracy()) l.accuracy else 0f,
        )
        gps.extra = String.format(Locale.ROOT, "%.5f° N, %.5f° Ø", l.latitude, l.longitude)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        gps.extra = "Posisjon er slått av på telefonen"
    }

    @Deprecated("Trengst for eldre Android-versjonar")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
    }
}

private val NB = Locale.forLanguageTag("nb-NO")

/** Tal med norsk desimalkomma og ekte minusteikn. */
fun fmt(v: Float, decimals: Int): String =
    String.format(NB, "%.${decimals}f", v).replace('-', '−')
