package no.frodolini.sensorlab

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Sender lydnivået til klasseskjermen (klasseskjerm.html på frodolini.github.io).
 *
 * Éi melding i sekundet går til databasefunksjonen lyd_send i Supabase, som sender ho vidare
 * til klasseskjermen. Funksjonen slepp berre gjennom meldingar med rett sendarnøkkel.
 * Nøkkelen ligg ikkje i koden: han blir skriven inn i appen og lagra på telefonen.
 *
 * Berre talet for lydnivået blir sendt. Sjølve lyden forlèt aldri telefonen.
 */
class LiveSender {
    companion object {
        const val IDLE = 0        // ikkje sendt noko enno
        const val OK = 1          // siste melding kom fram
        const val WRONG_KEY = 2   // databasen godtok ikkje sendarnøkkelen
        const val NO_NET = 3      // fekk ikkje kontakt

        private const val SEND_URL = "https://jweqcrfxcfwknthwafiy.supabase.co/rest/v1/rpc/lyd_send"

        // Offentleg nøkkel, den same som står i nettsidene. Han gjev ikkje løyve til å senda.
        private const val API_KEY = "sb_publishable_AVk07xriWcOERP6DBPpoQw_3NCktPNP"

        /** Godtek nøkkelen med eller utan bindestrek og med store bokstavar: «BDB719B284C2» → «bdb7-19b2-84c2». */
        fun normalizeKey(text: String): String {
            val hex = text.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
            return if (hex.length == 12) hex.chunked(4).joinToString("-") else text.trim().lowercase()
        }
    }

    @Volatile var status = IDLE

    // Éin bakgrunnstråd som forsvinn av seg sjølv når han har vore ledig i 30 sekund.
    private val exec = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val busy = AtomicBoolean(false)

    /** Send dei siste målingane (eldste først). Er førre melding ikkje ferdig, blir denne hoppa over. */
    fun sendLevels(key: String, timeMs: Long, stepMs: Int, levels: FloatArray, limit: Float, redSeconds: Long) {
        if (!busy.compareAndSet(false, true)) return
        val data = JSONObject()
            .put("t", timeMs)
            .put("dt", stepMs)
            .put("lyd", JSONArray().apply { levels.forEach { put((it * 10).roundToInt() / 10.0) } })
            .put("grense", limit.roundToInt())
            .put("tidIRaudt", redSeconds)
        exec.execute {
            try { status = post(key, data) } finally { busy.set(false) }
        }
    }

    /** Sei frå til klasseskjermen at sendinga er slått av, så han ikkje står og ventar. */
    fun sendStop(key: String) {
        exec.execute {
            post(key, JSONObject().put("slutt", true))
            status = IDLE
        }
    }

    private fun post(key: String, data: JSONObject): Int {
        return try {
            val body = JSONObject().put("p_nokkel", key).put("p_data", data).toString().toByteArray()
            val c = URL(SEND_URL).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.doOutput = true
            c.setRequestProperty("apikey", API_KEY)
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            if (c.responseCode in 200..299) {
                // Les ferdig svaret, så tilkoplinga kan brukast om att til neste melding.
                runCatching { c.inputStream.use { it.readBytes() } }
                OK
            } else {
                val msg = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                if ("sendarn" in msg) WRONG_KEY else NO_NET
            }
        } catch (_: Exception) {
            NO_NET
        }
    }
}
