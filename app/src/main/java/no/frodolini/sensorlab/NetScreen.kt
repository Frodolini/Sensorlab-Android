package no.frodolini.sensorlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Trafikkgrafen hugsar 120 punkt à eitt sekund = to minutt. */
private const val NET_HIST = 120

class Section(val title: String, val rows: List<Pair<String, String>>)

fun channelOf(mhz: Int): Int = when {
    mhz == 2484 -> 14
    mhz in 2412..2472 -> (mhz - 2407) / 5
    mhz in 5000..5895 -> (mhz - 5000) / 5
    mhz in 5955..7115 -> (mhz - 5950) / 5
    else -> 0
}

fun bandOf(mhz: Int): String = when {
    mhz >= 5925 -> "6 GHz"
    mhz >= 4900 -> "5 GHz"
    else -> "2,4 GHz"
}

fun standardName(s: Int): String = when (s) {
    1 -> "802.11a/b/g (eldre)"
    4 -> "Wi-Fi 4 (802.11n)"
    5 -> "Wi-Fi 5 (802.11ac)"
    6 -> "Wi-Fi 6 (802.11ax)"
    7 -> "802.11ad (60 GHz)"
    8 -> "Wi-Fi 7 (802.11be)"
    else -> ""
}

private fun securityTypeName(t: Int): String = when (t) {
    0 -> "Ope (utan kryptering)"
    1 -> "WEP (utrygt)"
    2 -> "WPA/WPA2-Personal (PSK)"
    3 -> "WPA/WPA2-Enterprise (802.1X)"
    4 -> "WPA3-Personal (SAE)"
    5 -> "WPA3-Enterprise 192-bit"
    6 -> "Ope, men kryptert (OWE)"
    9 -> "WPA3-Enterprise"
    11, 12 -> "Passpoint"
    else -> "Ukjend"
}

/** Tolkar tryggleiksteksten frå ei skanning, t.d. «[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]». */
fun securityOf(caps: String): String = when {
    "SAE" in caps && "PSK" in caps -> "WPA2/WPA3"
    "SAE" in caps -> "WPA3"
    "EAP" in caps -> "Enterprise (802.1X)"
    "PSK" in caps -> if ("RSN" in caps || "WPA2" in caps) "WPA2" else "WPA (gammal)"
    "WEP" in caps -> "WEP (utrygt)"
    "OWE" in caps -> "Ope, kryptert (OWE)"
    else -> "Ope"
}

private fun widthName(w: Int): String = when (w) {
    0 -> "20 MHz"
    1 -> "40 MHz"
    2 -> "80 MHz"
    3 -> "160 MHz"
    4 -> "80+80 MHz"
    5 -> "320 MHz"
    else -> ""
}

private fun maskOf(prefix: Int): String {
    val bits = if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceAtMost(32)))
    return "${bits ushr 24 and 255}.${bits ushr 16 and 255}.${bits ushr 8 and 255}.${bits and 255}"
}

private fun host(a: InetAddress?): String = a?.hostAddress?.substringBefore('%') ?: ""
private fun yesNo(b: Boolean) = if (b) "Ja" else "Nei"

private fun bytesText(b: Long): String = when {
    b < 0 -> "Ukjend"
    b >= 1_000_000_000L -> fmt(b / 1e9f, 2) + " GB"
    b >= 1_000_000L -> fmt(b / 1e6f, 1) + " MB"
    else -> fmt(b / 1e3f, 0) + " kB"
}

private fun rateText(mbit: Float): String =
    if (mbit >= 1f) fmt(mbit, 1) + " Mbit/s" else fmt(mbit * 1000f, 0) + " kbit/s"

/** Les alt som endrar seg: Wi-Fi-tilkopling, IP-oppsett og mobilnett. Køyrer kvart sekund. */
private fun readNet(ctx: Context, located: Boolean): List<Section> {
    val out = ArrayList<Section>()

    // --- Wi-Fi-tilkoplinga ---
    val wifi = ArrayList<Pair<String, String>>()
    try {
        val wm = ctx.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") val ci = wm?.connectionInfo
        if (wm == null || !wm.isWifiEnabled) {
            wifi += "Status" to "Wi-Fi er slått av"
        } else if (ci == null || ci.rssi <= -127 || ci.frequency <= 0) {
            wifi += "Status" to "Ikkje kopla til eit nett"
        } else {
            val ssid = ci.ssid?.removeSurrounding("\"") ?: ""
            val hidden = ssid.isEmpty() || ssid == WifiManager.UNKNOWN_SSID
            wifi += "Nettverksnamn (SSID)" to if (!hidden) ssid else if (located) "Ukjent" else "Krev posisjonsløyve"
            val bssid = ci.bssid
            wifi += "Aksesspunkt (BSSID)" to
                if (bssid == null || bssid == "02:00:00:00:00:00") (if (located) "Ukjent" else "Krev posisjonsløyve") else bssid
            wifi += "Signalstyrke (RSSI)" to "${ci.rssi} dBm".replace('-', '−')
            wifi += "Frekvens" to "${ci.frequency} MHz"
            wifi += "Kanal og band" to "Kanal ${channelOf(ci.frequency)} · ${bandOf(ci.frequency)}"
            if (Build.VERSION.SDK_INT >= 30) {
                val std = standardName(ci.wifiStandard)
                if (std.isNotEmpty()) wifi += "Wi-Fi-standard" to std
            }
            if (Build.VERSION.SDK_INT >= 31) wifi += "Tryggleik" to securityTypeName(ci.currentSecurityType)
            if (Build.VERSION.SDK_INT >= 29) {
                if (ci.txLinkSpeedMbps > 0) wifi += "Linkfart sending" to "${ci.txLinkSpeedMbps} Mbit/s"
                if (ci.rxLinkSpeedMbps > 0) wifi += "Linkfart mottak" to "${ci.rxLinkSpeedMbps} Mbit/s"
            } else {
                wifi += "Linkfart" to "${ci.linkSpeed} Mbit/s"
            }
            if (Build.VERSION.SDK_INT >= 30 && ci.maxSupportedTxLinkSpeedMbps > 0) {
                wifi += "Høgaste moglege linkfart" to "${ci.maxSupportedTxLinkSpeedMbps} Mbit/s"
            }
        }
        wifi += "Telefonen si eiga MAC" to "Skjult av Android. Telefonen brukar ei tilfeldig adresse per nett."
    } catch (e: Exception) {
        wifi += "Feil" to (e.message ?: "Kan ikkje lesa Wi-Fi")
    }
    out += Section("Wi-Fi-tilkoplinga", wifi)

    // --- IP-oppsettet på det aktive nettet ---
    val ip = ArrayList<Pair<String, String>>()
    try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val lp = if (net != null) cm.getLinkProperties(net) else null
        val nc = if (net != null) cm.getNetworkCapabilities(net) else null
        if (lp == null || nc == null) {
            ip += "Status" to "Ikkje noko aktivt nett"
        } else {
            val transports = ArrayList<String>()
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "Wi-Fi"
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "Mobilnett"
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "Kabel"
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "VPN"
            ip += "Aktivt nett" to transports.joinToString(" + ").ifEmpty { "Anna" }
            lp.interfaceName?.let { ip += "Grensesnitt" to it }
            for (la in lp.linkAddresses) {
                val a = la.address
                if (a is Inet4Address) {
                    ip += "IPv4-adresse" to "${host(a)}/${la.prefixLength}"
                    ip += "Nettmaske" to maskOf(la.prefixLength)
                } else {
                    ip += (if (a.isLinkLocalAddress) "IPv6 (lenkelokal)" else "IPv6-adresse") to "${host(a)}/${la.prefixLength}"
                }
            }
            val gateways = lp.routes.filter { it.isDefaultRoute }
                .mapNotNull { it.gateway }.filter { !it.isAnyLocalAddress }.map { host(it) }.distinct()
            if (gateways.isNotEmpty()) ip += "Standard gateway" to gateways.joinToString("\n")
            if (lp.dnsServers.isNotEmpty()) ip += "DNS-tenarar" to lp.dnsServers.joinToString("\n") { host(it) }
            if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.let { ip += "DHCP-tenar" to host(it) }
            lp.domains?.takeIf { it.isNotBlank() }?.let { ip += "Domene" to it }
            if (Build.VERSION.SDK_INT >= 29 && lp.mtu > 0) ip += "MTU" to "${lp.mtu} byte"
            if (Build.VERSION.SDK_INT >= 28) {
                ip += "Privat DNS (kryptert)" to
                    if (lp.isPrivateDnsActive) "På" + (lp.privateDnsServerName?.let { " ($it)" } ?: "") else "Av"
            }
            ip += "Internettilgang stadfesta" to yesNo(nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            ip += "Målt nett (kostar data)" to yesNo(!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            if (nc.linkDownstreamBandwidthKbps > 0) {
                ip += "Systemet sitt fartsoverslag" to
                    "ned ${rateText(nc.linkDownstreamBandwidthKbps / 1000f)}, opp ${rateText(nc.linkUpstreamBandwidthKbps / 1000f)}"
            }
        }
    } catch (e: Exception) {
        ip += "Feil" to (e.message ?: "Kan ikkje lesa IP-oppsettet")
    }
    out += Section("IP-oppsett", ip)

    // --- Mobilnettet ---
    val mob = ArrayList<Pair<String, String>>()
    try {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        if (tm == null || tm.simState == TelephonyManager.SIM_STATE_ABSENT) {
            mob += "Status" to "Ingen SIM"
        } else {
            tm.networkOperatorName?.takeIf { it.isNotBlank() }?.let { mob += "Operatør" to it }
            val op = tm.networkOperator
            if (op != null && op.length >= 5) mob += "Landskode og nettkode (MCC/MNC)" to "${op.substring(0, 3)} / ${op.substring(3)}"
            tm.networkCountryIso?.takeIf { it.isNotBlank() }?.let { mob += "Land" to it.uppercase() }
            tm.simOperatorName?.takeIf { it.isNotBlank() }?.let { mob += "SIM frå" to it }
            mob += "Roaming" to yesNo(tm.isNetworkRoaming)
            if (Build.VERSION.SDK_INT >= 28) {
                val ss = tm.signalStrength
                if (ss != null) {
                    mob += "Signalnivå" to "${ss.level} av 4"
                    if (Build.VERSION.SDK_INT >= 29) {
                        for (c in ss.cellSignalStrengths) {
                            val type = when (c) {
                                is CellSignalStrengthNr -> "5G (NR)"
                                is CellSignalStrengthLte -> "4G (LTE)"
                                is CellSignalStrengthWcdma -> "3G"
                                is CellSignalStrengthGsm -> "2G"
                                else -> "Anna"
                            }
                            if (c.dbm in -160..-1) mob += "Signal $type" to "${c.dbm} dBm".replace('-', '−')
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        mob += "Feil" to (e.message ?: "Kan ikkje lesa mobilnettet")
    }
    out += Section("Mobilnett", mob)
    return out
}

/** Les det som ikkje endrar seg: grensesnitt og kva radioen støttar. */
private fun readStatic(ctx: Context): List<Section> {
    val out = ArrayList<Section>()
    val ifs = ArrayList<Pair<String, String>>()
    try {
        for (ni in NetworkInterface.getNetworkInterfaces().toList().sortedBy { it.name }) {
            if (!ni.isUp) continue
            val addrs = ni.inetAddresses.toList().map { host(it) }.filter { it.isNotEmpty() }
            if (addrs.isEmpty()) continue
            val kind = when {
                ni.isLoopback -> "loopback"
                ni.name.startsWith("wlan") -> "Wi-Fi"
                ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni") -> "mobildata"
                ni.name.startsWith("tun") || ni.name.startsWith("ppp") -> "VPN/tunnel"
                else -> ""
            }
            ifs += (ni.name + if (kind.isNotEmpty()) " ($kind)" else "") to
                (listOf("MTU ${ni.mtu}") + addrs).joinToString("\n")
        }
    } catch (_: Exception) {
    }
    if (ifs.isEmpty()) ifs += "Status" to "Android gjev ikkje denne appen lista over grensesnitt"
    out += Section("Nettverksgrensesnitt som er oppe", ifs)

    val cap = ArrayList<Pair<String, String>>()
    try {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val pm = ctx.packageManager
        if (wm != null) {
            cap += "5 GHz" to yesNo(wm.is5GHzBandSupported)
            if (Build.VERSION.SDK_INT >= 30) {
                cap += "6 GHz (Wi-Fi 6E)" to yesNo(wm.is6GHzBandSupported)
                cap += "Wi-Fi 6 (802.11ax)" to yesNo(wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX))
            }
            if (Build.VERSION.SDK_INT >= 33) {
                cap += "Wi-Fi 7 (802.11be)" to yesNo(wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE))
            }
            if (Build.VERSION.SDK_INT >= 29) cap += "WPA3" to yesNo(wm.isWpa3SaeSupported)
            cap += "Wi-Fi Direct" to yesNo(wm.isP2pSupported)
        }
        cap += "Avstandsmåling med Wi-Fi (RTT)" to yesNo(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT))
        cap += "Bluetooth Low Energy" to yesNo(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        cap += "NFC" to yesNo(pm.hasSystemFeature(PackageManager.FEATURE_NFC))
        cap += "UWB (ultrabreiband)" to yesNo(pm.hasSystemFeature("android.hardware.uwb"))
    } catch (_: Exception) {
    }
    out += Section("Radioane i telefonen støttar", cap)
    return out
}

@Composable
fun NetScreen(hub: SensorHub, onBack: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hub.startGps() }
    val located = hub.gpsGranted

    var sections by remember { mutableStateOf(emptyList<Section>()) }
    val static = remember { readStatic(ctx) }
    val down = remember { FloatArray(NET_HIST) }
    val up = remember { FloatArray(NET_HIST) }
    var samples by remember { mutableIntStateOf(0) }
    var rate by remember { mutableStateOf(0f to 0f) }
    var totals by remember { mutableStateOf(0L to 0L) }

    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, located) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var lastRx = TrafficStats.getTotalRxBytes()
            var lastTx = TrafficStats.getTotalTxBytes()
            var lastT = SystemClock.elapsedRealtime()
            while (true) {
                sections = withContext(Dispatchers.IO) { readNet(ctx, located) }
                delay(1000)
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                val t = SystemClock.elapsedRealtime()
                val dt = ((t - lastT) / 1000f).coerceAtLeast(0.1f)
                val d = ((rx - lastRx).coerceAtLeast(0) * 8 / 1e6f) / dt
                val u = ((tx - lastTx).coerceAtLeast(0) * 8 / 1e6f) / dt
                down[samples % NET_HIST] = d
                up[samples % NET_HIST] = u
                samples++
                rate = d to u
                totals = rx to tx
                lastRx = rx; lastTx = tx; lastT = t
            }
        }
    }

    Scaffold(topBar = { GreenBar("Nettverk", onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!located) {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Android reknar nettverksnamn og nett i nærleiken som posisjonsdata. Gje posisjonsløyve for å sjå dei. Ingenting blir lagra eller sendt nokon stad.")
                        Button(onClick = {
                            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Text("Gje løyve til posisjon") }
                    }
                }
            }
            sections.getOrNull(0)?.let { SectionCard(it) }
            sections.getOrNull(1)?.let { SectionCard(it) }

            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Trafikk akkurat no", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Ned: ${rateText(rate.first)}", color = AxisColors[2], fontWeight = FontWeight.Bold)
                        Text("Opp: ${rateText(rate.second)}", color = AxisColors[0], fontWeight = FontWeight.Bold)
                    }
                    val n = minOf(samples, NET_HIST)
                    val start = if (samples > NET_HIST) samples % NET_HIST else 0
                    LineChart(
                        series = listOf(
                            FloatArray(n) { down[(start + it) % NET_HIST] },
                            FloatArray(n) { up[(start + it) % NET_HIST] },
                        ),
                        colors = listOf(AxisColors[2], AxisColors[0]),
                        decimals = 2, fixedMin = 0f, capacity = NET_HIST,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                    )
                    Text(
                        "Mbit/s, siste to minutt. All trafikk på telefonen, ikkje berre denne appen.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InfoRow("Motteke sidan oppstart", bytesText(totals.first))
                    InfoRow("Sendt sidan oppstart", bytesText(totals.second))
                }
            }

            ScanCard(ctx, located) {
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            sections.getOrNull(2)?.let { SectionCard(it) }
            for (s in static) SectionCard(s)
        }
    }
}

@Composable
private fun SectionCard(s: Section) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.title, fontWeight = FontWeight.Bold)
            for ((k, v) in s.rows) InfoRow(k, v)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(0.9f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun signalColor(dbm: Int): Color = when {
    dbm >= -60 -> SoundGreen
    dbm >= -75 -> Color(0xFFB7791F)
    else -> SoundRed
}

@SuppressLint("MissingPermission")
@Composable
private fun ScanCard(ctx: Context, located: Boolean, askPermission: () -> Unit) {
    val wm = remember { ctx.getSystemService(WifiManager::class.java) }
    var results by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    fun load() {
        results = try {
            wm?.scanResults.orEmpty().sortedByDescending { it.level }
        } catch (_: Exception) {
            emptyList()
        }
    }

    DisposableEffect(located) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                load()
                val fresh = i?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true) != false
                status = if (fresh) "Ferske resultat" else "Skanninga vart avvist av Android. Viser siste resultat."
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (located) load()
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nett i nærleiken", fontWeight = FontWeight.Bold)
            Button(onClick = {
                if (!located) {
                    askPermission()
                } else {
                    val lm = ctx.getSystemService(LocationManager::class.java)
                    val locationOn = Build.VERSION.SDK_INT < 28 || lm?.isLocationEnabled == true
                    if (!locationOn) {
                        status = "Slå på posisjon på telefonen for å kunna skanna."
                    } else {
                        @Suppress("DEPRECATION") val ok = try { wm?.startScan() == true } catch (_: Exception) { false }
                        if (ok) {
                            status = "Skannar …"
                        } else {
                            status = "Android avviste skanninga. Grensa er om lag fire skanningar per to minutt. Viser siste resultat."
                            load()
                        }
                    }
                }
            }) { Text(if (located) "Skann no" else "Gje løyve til posisjon") }
            if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (results.isNotEmpty()) {
                val byBand = results.groupingBy { bandOf(it.frequency) }.eachCount()
                Text(
                    "${results.size} nett: " + listOf("2,4 GHz", "5 GHz", "6 GHz").filter { it in byBand }
                        .joinToString(" · ") { "${byBand[it]} på $it" },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
                val ch24 = results.filter { it.frequency < 4900 }.groupingBy { channelOf(it.frequency) }.eachCount().toSortedMap()
                if (ch24.isNotEmpty()) {
                    Text(
                        "Kanalbruk på 2,4 GHz: " + ch24.entries.joinToString(" · ") { "kanal ${it.key}: ${it.value}" },
                        fontSize = 13.sp,
                    )
                }
                for (r in results) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            @Suppress("DEPRECATION") val name = r.SSID
                            Text(if (name.isNullOrEmpty()) "(skjult nett)" else name, fontWeight = FontWeight.SemiBold)
                            val std = if (Build.VERSION.SDK_INT >= 30) standardName(r.wifiStandard) else ""
                            Text(
                                listOf(
                                    "kanal ${channelOf(r.frequency)}", bandOf(r.frequency), widthName(r.channelWidth),
                                    securityOf(r.capabilities ?: ""), std,
                                ).filter { it.isNotEmpty() }.joinToString(" · "),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(r.BSSID ?: "", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "${r.level} dBm".replace('-', '−'),
                            color = signalColor(r.level), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            } else if (located) {
                Text("Ingen resultat enno. Trykk «Skann no».", fontSize = 13.sp)
            }
        }
    }
}
