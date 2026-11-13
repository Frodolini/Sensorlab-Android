package no.frodolini.sensorlab

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

val FrodeGreen = Color(0xFF1F8A3B)
val AxisColors = listOf(Color(0xFFD9483B), Color(0xFF2E9E4F), Color(0xFF2F7DD1))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val hub = SensorHub(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF6FD08A)) else lightColorScheme(primary = FrodeGreen)
            MaterialTheme(colorScheme = scheme) { App(hub) }
        }
    }
}

@Composable
fun App(hub: SensorHub) {
    var screen by rememberSaveable { mutableStateOf("home") }
    val owner = LocalLifecycleOwner.current
    // Sensorane er berre på medan appen er framme på skjermen.
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hub.start()
            try {
                while (true) {
                    delay(TICK_MS)
                    hub.tick()
                }
            } finally {
                hub.stop()
            }
        }
    }
    BackHandler(enabled = screen != "home") { screen = "home" }
    val source = hub.sources.firstOrNull { "d:" + it.id == screen }
    when {
        screen == "sound" -> SoundScreen(onBack = { screen = "home" })
        screen == "net" -> NetScreen(hub, onBack = { screen = "home" })
        source != null -> DetailScreen(hub, source, onBack = { screen = "home" })
        else -> HomeScreen(hub, open = { screen = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreenBar(title: String, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tilbake")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = FrodeGreen,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
        ),
    )
}

@Composable
fun HomeScreen(hub: SensorHub, open: (String) -> Unit) {
    hub.otherLive // bygg lista på nytt når ein ny sensor byrjar å levera data
    val groups = listOf(
        GROUP_MAIN to "Sensorar",
        GROUP_PHONE to "Telefon og nett",
        GROUP_OTHER to "Andre sensorar telefonen melder",
    )
    Scaffold(topBar = { GreenBar("Sensorlab") }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 165.dp),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = pad.calculateTopPadding() + 12.dp,
                bottom = pad.calculateBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SoundCard(onClick = { open("sound") }) }
            item(span = { GridItemSpan(maxLineSpan) }) { NetCard(onClick = { open("net") }) }
            for ((group, label) in groups) {
                val all = hub.sources.filter { it.group == group }
                // Under «andre» viser me berre sensorar som faktisk leverer data.
                val list = if (group == GROUP_OTHER) all.filter { it.latest != null } else all
                val silent = all.size - list.size
                if (all.isEmpty()) continue
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "$label (${list.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                items(list, key = { it.id }) { src -> Tile(hub, src, onClick = { open("d:" + src.id) }) }
                if (silent > 0) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "$silent sensorar til er melde av telefonen, men leverer ikkje data til vanlege appar. Dei er reserverte for systemet og produsenten sine eigne appar.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun SoundCard(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FrodeGreen, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lydmålar for timen", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Trafikklys som viser lydnivået i klasserommet", fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in listOf(SoundGreen, SoundYellow, SoundRed)) {
                    Canvas(Modifier.size(18.dp)) {
                        drawCircle(Color.White)
                        drawCircle(c, radius = size.minDimension / 2 - 2.dp.toPx())
                    }
                }
            }
        }
    }
}

@Composable
fun NetCard(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14532D), contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Nettverk", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Wi-Fi, IP-oppsett, trafikk, mobilnett og nett i nærleiken", fontSize = 14.sp)
        }
    }
}

@Composable
fun Tile(hub: SensorHub, src: Source, onClick: () -> Unit) {
    hub.slowFrame // les for å oppdatera fire gonger i sekundet
    val v = src.latest
    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 118.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                src.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            when {
                src === hub.gps && !hub.gpsGranted -> Text("Trykk for å slå på", fontSize = 14.sp)
                v == null -> Text(
                    src.extra.ifEmpty { "Ventar på data …" }, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                src.axes.size == 1 -> {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(fmt(v[0], src.decimals), fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(6.dp))
                        Text(src.unit, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                    if (src.extra.isNotEmpty()) Text(
                        src.extra, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    for (i in src.axes.indices) {
                        Row {
                            Text(
                                src.axes[i], fontSize = 13.sp, color = AxisColors[i % 3],
                                fontWeight = FontWeight.Bold, modifier = Modifier.width(if (src === hub.gps) 96.dp else 18.dp),
                            )
                            Text(fmt(v[i], src.decimals), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (src.unit.isNotEmpty() && src !== hub.gps) {
                        Text(src.unit, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun DetailScreen(hub: SensorHub, src: Source, onBack: () -> Unit) {
    var paused by rememberSaveable(src.id) { mutableStateOf(false) }
    DisposableEffect(src) { onDispose { src.paused = false } }
    src.paused = paused
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hub.startGps() }
    val needsPermission = src === hub.gps && !hub.gpsGranted

    Scaffold(topBar = { GreenBar(src.title, onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (needsPermission) {
                Text("GPS-flisa treng løyve til posisjon. Posisjonen blir berre vist her på skjermen og blir ikkje lagra eller sendt nokon stad.")
                Button(onClick = {
                    askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Gje løyve til posisjon") }
            } else {
                LiveValues(hub, src)
                LiveChart(hub, src)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { paused = !paused }) { Text(if (paused) "Hald fram" else "Pause") }
                    OutlinedButton(onClick = { src.clear() }) { Text("Nullstill") }
                }
                LiveStats(hub, src)
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Kva måler denne?", fontWeight = FontWeight.Bold)
                    Text(src.info)
                }
            }
            if (src.tech.isNotEmpty()) {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Teknisk om sensoren", fontWeight = FontWeight.Bold)
                        Text(src.tech, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveValues(hub: SensorHub, src: Source) {
    hub.slowFrame
    val v = src.latest
    Column {
        if (v == null) {
            Text(src.extra.ifEmpty { "Ventar på data …" }, fontSize = 20.sp)
        } else if (src.axes.size == 1) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt(v[0], src.decimals), fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text(src.unit, fontSize = 24.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (i in src.axes.indices) {
                    Column {
                        Text(src.axes[i], color = AxisColors[i % 3], fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(fmt(v[i], src.decimals), fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (src.unit.isNotEmpty()) Text(src.unit, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (v != null && src.extra.isNotEmpty()) {
            Text(src.extra, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LiveChart(hub: SensorHub, src: Source) {
    hub.frame // teikn på nytt 20 gonger i sekundet
    val series = List(src.axes.size) { src.series(it) }
    Column {
        LineChart(
            series = series,
            colors = if (src.axes.size == 1) listOf(MaterialTheme.colorScheme.primary) else AxisColors,
            decimals = src.decimals,
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
        Text(
            "Siste 30 sekund", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LiveStats(hub: SensorHub, src: Source) {
    hub.slowFrame
    if (src.count == 0) return
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row {
                Text("", Modifier.weight(1.2f))
                for (h in listOf("min", "snitt", "maks")) {
                    Text(h, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            for (i in src.axes.indices) {
                val s = src.series(i)
                var mn = Float.MAX_VALUE
                var mx = -Float.MAX_VALUE
                var sum = 0.0
                for (x in s) {
                    if (x < mn) mn = x
                    if (x > mx) mx = x
                    sum += x
                }
                Row {
                    Text(
                        src.axes[i].ifEmpty { src.unit }, Modifier.weight(1.2f),
                        color = if (src.axes.size == 1) MaterialTheme.colorScheme.onSurface else AxisColors[i % 3],
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
                    )
                    for (x in listOf(mn, (sum / s.size).toFloat(), mx)) {
                        Text(fmt(x, src.decimals), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
