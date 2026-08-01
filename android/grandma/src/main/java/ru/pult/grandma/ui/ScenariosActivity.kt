package ru.pult.grandma.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.pult.grandma.control.RemoteControlService
import ru.pult.grandma.control.Scenario
import ru.pult.grandma.control.ScenarioPlayer
import ru.pult.grandma.control.ScenarioStore

/**
 * Экран сценариев. Его открывает внук, сидя рядом с бабушкой, — или сама бабушка,
 * чтобы ПОКАЗАТЬ, как она обычно делает.
 *
 * Запись идёт локально, на самом телефоне: сеть в этот момент не участвует, и внук
 * своими глазами видит, что записалось. Сохранённый сценарий становится крупной кнопкой,
 * которую бабушка жмёт сама, — в этом и смысл: не «внук чинит», а «бабушка снова может».
 */
class ScenariosActivity : AppCompatActivity() {

    private val store by lazy { ScenarioStore(filesDir) }
    private lateinit var root: LinearLayout
    private lateinit var recordButton: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Тема рисует панель заголовка поверх содержимого, поэтому верхний отступ
            // считаем сами: высота панели + статус-бар, иначе подпись уезжает под них.
            setPadding(dp(20), topInset() + dp(12), dp(20), dp(24))
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, dp(12))
        }
        recordButton = Button(this).apply {
            textSize = 20f
            setOnClickListener { toggleRecording() }
        }
        root.addView(status)
        root.addView(recordButton, LinearLayout.LayoutParams(MATCH, dp(72)))

        // Список растёт — оборачиваем в прокрутку. Иначе после десятка сценариев
        // нижние кнопки станут недостижимы.
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ── Запись ────────────────────────────────────────────────────────────────

    private fun toggleRecording() {
        val service = RemoteControlService.instance
        if (service == null) {
            Toast.makeText(this, "Служба управления выключена", Toast.LENGTH_LONG).show()
            return
        }
        if (!service.recorder.isRecording) {
            // На время записи открываем область службы (нужно захватить новое приложение);
            // вне записи она ограничена приложениями сценариев (§177).
            service.applyScope(openForRecording = true)
            service.recorder.start()
            // Уходим с глаз: человек должен выполнять действия в обычных приложениях,
            // а не внутри Пульта. Нажатия в самом Пульте запись игнорирует.
            moveTaskToBack(true)
            Toast.makeText(this, "Записываю. Вернитесь в Пульт и нажмите «Стоп»", Toast.LENGTH_LONG).show()
        } else {
            val steps = service.recorder.stop()
            // Возвращаем область к приложениям сценариев (в т.ч. только что записанному).
            service.applyScope(openForRecording = false)
            if (steps.isEmpty()) {
                Toast.makeText(this, "Ничего не записалось", Toast.LENGTH_LONG).show()
            } else {
                askName(steps)
            }
        }
        render()
    }

    private fun askName(steps: List<ru.pult.grandma.control.Step>) {
        val input = android.widget.EditText(this).apply { hint = "Например: Позвонить Маше" }
        val summary = steps.joinToString("\n") { "• ${it.describe()}" }
        AlertDialog.Builder(this)
            .setTitle("Записано шагов: ${steps.size}")
            .setMessage(summary.take(1200))
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().ifBlank { "Сценарий ${store.load().size + 1}" }
                store.add(
                    Scenario(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        createdAt = System.currentTimeMillis(),
                        steps = steps,
                    ),
                )
                render()
            }
            .setNegativeButton("Отменить", null)
            .show()
    }

    // ── Воспроизведение ───────────────────────────────────────────────────────

    private fun play(scenario: Scenario) {
        val service = RemoteControlService.instance ?: return
        moveTaskToBack(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { service.player.play(scenario) }
            withContext(Dispatchers.Main) {
                val message = when (result) {
                    is ScenarioPlayer.Result.Done -> "Готово: ${scenario.name}"
                    is ScenarioPlayer.Result.Stopped ->
                        "Остановлено на шаге ${result.atStep + 1}: ${result.reason}"
                }
                Toast.makeText(this@ScenariosActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Отрисовка ─────────────────────────────────────────────────────────────

    private fun render() {
        val service = RemoteControlService.instance
        val recording = service?.recorder?.isRecording == true

        status.text = when {
            service == null -> "Служба управления выключена — включите её в настройках доступности"
            recording -> "Идёт запись. Выполните действия, затем вернитесь и нажмите «Стоп»"
            else -> "Нажмите «Записать», покажите действие — оно станет кнопкой"
        }
        recordButton.text = if (recording) "Стоп" else "Записать сценарий"

        // Список сохранённых сценариев — крупными кнопками.
        while (root.childCount > 2) root.removeViewAt(2)
        store.load().forEach { scenario ->
            root.addView(
                Button(this).apply {
                    text = "${scenario.name}  (${scenario.steps.size})"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setOnClickListener { play(scenario) }
                    setOnLongClickListener { confirmDelete(scenario); true }
                },
                LinearLayout.LayoutParams(MATCH, dp(80)).apply { topMargin = dp(10) },
            )
        }
    }

    private fun confirmDelete(scenario: Scenario) {
        AlertDialog.Builder(this)
            .setTitle(scenario.name)
            .setMessage("Удалить этот сценарий?")
            .setPositiveButton("Удалить") { _, _ -> store.remove(scenario.id); render() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Высота панели заголовка вместе со строкой состояния. */
    private fun topInset(): Int {
        val tv = android.util.TypedValue()
        val bar = if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        } else {
            dp(56)
        }
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (statusId > 0) resources.getDimensionPixelSize(statusId) else dp(24)
        return bar + status
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    }
}
