package no.frodolini.sensorlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.ToneGenerator
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

val SoundGreen = Color(0xFF2E9E4F)
val SoundYellow = Color(0xFFF0B429)
val SoundRed = Color(0xFFD93A3A)

/** Les mikrofonen og reknar ut eit lydnivå. Ingen lyd blir lagra. */
class SoundEngine {
    /** Relativt nivå, om lag 0–100. Ikkje kalibrerte desibel. */
    @Volatile var db = 0f
    @Volatile var failed = false
    @Volatile private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        failed = false
        worker = thread(name = "lydmalar") {
            val rate = 44100
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var rec: AudioRecord? = null
            try {
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192),
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    failed = true
                    return@thread
                }
                val buf = ShortArray(2048)
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val x = buf[i].toDouble()
                        sum += x * x
                    }
                    val rms = sqrt(sum / n)
                    // dBFS (0 = fullt utslag) flytta opp til ein skala som liknar vanlege dB-tal.
                    db = (20.0 * log10(max(rms, 1.0) / 32768.0) + 94.0).toFloat().coerceIn(0f, 100f)
                }
            } catch (_: Exception) {
                failed = true
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                running = false
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }
}

@Composable
fun SoundScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    // Hald skjermen på medan lydmålaren er open.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val engine = remember { SoundEngine() }
    var limit by rememberSaveable { mutableStateOf(65f) }
    var alarm by rememberSaveable { mutableStateOf(false) }
    // Pipetone til lydalarmen. Følgjer medievolumet på telefonen.
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull() }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    var level by remember { mutableFloatStateOf(0f) }   // roleg nivå, styrer fargen
    var redMs by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableIntStateOf(0) }
    val history = remember { FloatArray(HIST) }
    var count by remember { mutableIntStateOf(0) }

    val owner = LocalLifecycleOwner.current
    LaunchedEffect(granted, owner) {
        if (!granted) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            engine.start()
            try {
                var smooth = -1f
                var redFor = 0      // samanhengande tid i raudt, ms
                var muteLeft = 0    // pause i målinga medan alarmen pip, ms
                var cooldown = 0    // minste tid til neste alarm, ms
                while (true) {
                    delay(100)
                    val raw = engine.db
                    if (muteLeft > 0) {
                        // Ikkje mål vår eigen pipetone, elles held alarmen seg sjølv i gang.
                        muteLeft -= 100
                    } else {
                        // Glatting over om lag eitt sekund, så fargen ikkje blinkar på kvar lyd.
                        smooth = if (smooth < 0f) raw else smooth + (raw - smooth) * 0.12f
                    }
                    if (smooth >= limit) redFor += 100 else redFor = 0
                    if (cooldown > 0) cooldown -= 100
                    // Alarm etter 3 sekund i raudt, og så kvart 8. sekund så lenge det er for høgt.
                    if (alarm && redFor >= 3000 && cooldown <= 0) {
                        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
                        muteLeft = 1000
                        cooldown = 8000
                    }
                    level = smooth
                    history[count % HIST] = smooth
                    count++
                    if (smooth >= limit) redMs += 100
                    frame++
                }
            } finally {
                engine.stop()
            }
        }
    }

    val yellowFrom = limit - 8f
    val target = when {
        !granted -> Color(0xFF607D8B)
        level >= limit -> SoundRed
        level >= yellowFrom -> SoundYellow
        else -> SoundGreen
    }
    val bg by animateColorAsState(target, tween(400), label = "bakgrunn")
    val message = when {
        level >= limit -> "For høgt!"
        level >= yellowFrom -> "Litt høgt"
        else -> "Fint arbeidsnivå"
    }

    Scaffold(topBar = { GreenBar("Lydmålar for timen", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).background(bg)) {
            if (!granted) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Lydmålaren treng løyve til mikrofonen. Appen reknar berre ut lydnivået. Ingen lyd blir lagra eller sendt nokon stad.",
                        color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center,
                    )
                    Button(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }, modifier = Modifier.padding(top = 20.dp)) {
                        Text("Gje løyve til mikrofon")
                    }
                }
            } else {
                val meter: @Composable (Modifier, Boolean) -> Unit = { m, wide ->
                    Box(m, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (engine.failed) "–" else level.roundToInt().toString(),
                                color = Color.White, fontSize = if (wide) 170.sp else 150.sp, fontWeight = FontWeight.Black,
                            )
                            Text(
                                if (engine.failed) "Får ikkje tilgang til mikrofonen" else message,
                                color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                val panel: @Composable (Modifier, RoundedCornerShape) -> Unit = { m, shape ->
                    Surface(shape = shape, modifier = m) {
                        Column(
                            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            frame // teikn grafen på nytt
                            val n = minOf(count, HIST)
                            val start = if (count > HIST) count % HIST else 0
                            val ordered = FloatArray(n) { history[(start + it) % HIST] }
                            LineChart(
                                series = listOf(ordered),
                                colors = listOf(MaterialTheme.colorScheme.onSurface),
                                decimals = 0, fixedMin = 20f, fixedMax = 100f, marker = limit,
                                modifier = Modifier.fillMaxWidth().height(70.dp),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Grense: ${limit.roundToInt()}", fontWeight = FontWeight.Bold)
                                Slider(
                                    value = limit, onValueChange = { limit = it.roundToInt().toFloat() },
                                    valueRange = 40f..90f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Lydalarm etter 3 s i raudt", modifier = Modifier.weight(1f))
                                Switch(checked = alarm, onCheckedChange = { alarm = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val s = redMs / 1000
                                Text("Tid i raudt: %d:%02d".format(s / 60, s % 60), modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = { redMs = 0L; count = 0 }) { Text("Nullstill") }
                            }
                            Text(
                                "Tala er relative og ikkje kalibrerte desibel. Still grensa etter kva som er passe i rommet. Grafen viser siste minuttet.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                // Liggjande (storskjerm): tal til venstre, kontrollar til høgre.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth > maxHeight) {
                        Row(Modifier.fillMaxSize()) {
                            meter(Modifier.weight(1f).fillMaxHeight(), true)
                            panel(Modifier.width(340.dp).fillMaxHeight(), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            meter(Modifier.weight(1f).fillMaxWidth(), false)
                            panel(Modifier.fillMaxWidth(), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        }
                    }
                }
            }
        }
    }
}
