package ru.pult.grandma.control

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Записанный сценарий — «позвонить Маше», «открыть ютуб».
 *
 * Ключевое решение: каждый шаг хранит СРАЗУ ДВА представления одного действия.
 *
 *  1. Семантика (`viewId`/`text`/`desc`) — «нажать кнопку *Позвонить*». Переживает
 *     переезд элемента, смену разрешения экрана, другую прокрутку.
 *  2. Координаты (`x`,`y` в долях экрана) — запасной путь, когда элемент не нашёлся
 *     (перерисовали приложение, элемент без текста и идентификатора).
 *
 * По отдельности каждый способ ломается: семантика — когда приложение обновили,
 * координаты — когда что-то сдвинулось. Вместе они закрывают дыры друг друга.
 *
 * `expectPackage` — контрольная точка: перед шагом проверяем, что мы там, где были при
 * записи. Не совпало → останавливаемся и зовём внука, а не жмём наугад по чужому экрану.
 */
@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val createdAt: Long,
    val steps: List<Step> = emptyList(),
)

@Serializable
data class Step(
    /** "launch" — запуск приложения, "click" — нажатие, "text" — ввод текста. */
    val kind: String,

    /** Пауза перед шагом (как при записи), чтобы экран успел отрисоваться. */
    val delayMs: Long = 0,

    /** Контрольная точка: приложение, в котором шаг был записан. */
    val expectPackage: String? = null,

    // ── Слой 1: запуск приложения — самый надёжный путь, без координат вообще ──
    val launchPackage: String? = null,

    // ── Слой 2: семантика элемента ──
    val viewId: String? = null,
    val text: String? = null,
    val desc: String? = null,

    // ── Слой 3: координаты в долях экрана (0..1) ──
    val x: Float? = null,
    val y: Float? = null,

    // ── kind == "swipe": прокрутка от (x1,y1) к (x2,y2), доли экрана ──
    val x1: Float? = null,
    val y1: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,

    /** Для kind == "text". */
    val inputText: String? = null,
) {
    /** Человекочитаемое описание — его показываем внуку при сохранении. */
    fun describe(): String = when (kind) {
        "launch" -> "Открыть ${launchPackage.orEmpty()}"
        "text" -> "Ввести «${inputText.orEmpty()}»"
        "swipe" -> if ((y1 ?: 0f) > (y2 ?: 0f)) "Пролистать вниз" else "Пролистать вверх"
        else -> {
            val label = text ?: desc ?: viewId?.substringAfterLast('/') ?: "элемент"
            val where = if (x != null && y != null) " (есть и координаты)" else ""
            "Нажать «$label»$where"
        }
    }
}

/**
 * Хранилище сценариев — обычный JSON-файл во внутренней памяти приложения.
 * Наружу не уходит: сценарий — это карта действий по личному телефону.
 */
class ScenarioStore(private val dir: File) {

    private val file: File get() = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): List<Scenario> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString<List<Scenario>>(file.readText())
    }.getOrDefault(emptyList())

    fun save(scenarios: List<Scenario>) {
        runCatching {
            file.writeText(json.encodeToString<List<Scenario>>(scenarios))
        }
    }

    fun add(scenario: Scenario) = save(load().filter { it.id != scenario.id } + scenario)

    fun remove(id: String) = save(load().filter { it.id != id })

    fun byId(id: String): Scenario? = load().firstOrNull { it.id == id }

    private companion object {
        const val FILE_NAME = "scenarios.json"
    }
}
