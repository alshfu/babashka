package ru.pult.grandma.session

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Локальный журнал сессий — источник истины (docs/protocol.md §7).
 *
 * Пишутся ВСЕ запросы, включая отклонённые, оставшиеся без ответа и провалившие проверку
 * пары: прозрачность выборочной не бывает. Содержимого помощи здесь нет — только факт,
 * время и исход.
 *
 * Формат — JSON Lines: его переживает любое обновление приложения и легко показать семье.
 */
class SessionJournal(context: Context) {

    private val file = File(context.filesDir, "sessions.jsonl")

    data class Entry(
        val sessionId: String,
        val peerName: String,
        val requestedAt: Long,
        val consented: Boolean,
        val endedAt: Long,
        val reason: String,
        val screenShown: Boolean,
    )

    @Synchronized
    fun append(entry: Entry) {
        val line = buildString {
            append('{')
            append("\"sessionId\":\"").append(entry.sessionId.escape()).append("\",")
            append("\"peerName\":\"").append(entry.peerName.escape()).append("\",")
            append("\"requestedAt\":").append(entry.requestedAt).append(',')
            append("\"consented\":").append(entry.consented).append(',')
            append("\"endedAt\":").append(entry.endedAt).append(',')
            append("\"reason\":\"").append(entry.reason.escape()).append("\",")
            append("\"screenShown\":").append(entry.screenShown)
            append('}')
        }
        file.appendText("$line\n")
        prune()
    }

    /** Человекочитаемый вид для экрана «Кто и когда помогал». */
    fun readable(): List<String> = read().map { entry ->
        val when1 = dateFormat.format(Date(entry.requestedAt))
        val outcome = when {
            entry.screenShown -> "показ экрана, ${(entry.endedAt - entry.requestedAt) / 1000} с"
            entry.consented -> "разрешено, но показ не состоялся (${entry.reason})"
            entry.reason == "declined" -> "вы отказали"
            entry.reason == "no-answer" -> "вы не ответили"
            entry.reason == "auth-failed" -> "чужое устройство не пустили"
            else -> entry.reason
        }
        "$when1 · ${entry.peerName} · $outcome"
    }

    fun read(): List<Entry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(::parse)
    }

    /** Журнал хранится год: он нужен семье, а не аналитике. */
    private fun prune(now: Long = System.currentTimeMillis()) {
        val entries = read().filter { now - it.requestedAt <= RETENTION_MS }
        if (entries.size == read().size) return
        file.writeText("")
        entries.forEach(::append)
    }

    private fun parse(line: String): Entry? = runCatching {
        fun str(key: String) = Regex("\"$key\":\"([^\"]*)\"").find(line)!!.groupValues[1]
        fun num(key: String) = Regex("\"$key\":(-?\\d+)").find(line)!!.groupValues[1].toLong()
        fun flag(key: String) = Regex("\"$key\":(true|false)").find(line)!!.groupValues[1].toBoolean()
        Entry(
            sessionId = str("sessionId"),
            peerName = str("peerName"),
            requestedAt = num("requestedAt"),
            consented = flag("consented"),
            endedAt = num("endedAt"),
            reason = str("reason"),
            screenShown = flag("screenShown"),
        )
    }.getOrNull()

    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val RETENTION_MS = 365L * 24 * 60 * 60 * 1000
        val dateFormat = SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru"))
    }
}
