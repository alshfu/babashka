package ru.pult.grandma.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.pult.grandma.R
import ru.pult.grandma.service.PultService
import ru.pult.grandma.setup.SetupStep

/**
 * Пошаговый мастер выживания сервиса — сердце настройки под MIUI/EMUI/ColorOS.
 *
 * Проходит семья при настройке телефона бабушки. По одному разрешению за раз: объяснение,
 * большая кнопка «Открыть настройки», проверка после возврата. Нельзя просто кинуть человека
 * в системные настройки — нужно вести за руку, иначе 70–80% не осилят (см. план, Этап 3).
 *
 * Пройденные шаги запоминаются: повторно открытый мастер не мучает тем, что уже сделано.
 */
class SetupActivity : AppCompatActivity() {

    private val steps = SetupStep.all()
    private var index = 0
    private var introShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Экран 0 — введение. Один раз объясняем, зачем всё это.
        showIntro()

        findViewById<Button>(R.id.open).setOnClickListener { openCurrent() }
        findViewById<Button>(R.id.next).setOnClickListener { onPrimary() }
        findViewById<Button>(R.id.skip).setOnClickListener { skipCurrent() }
    }

    private fun showIntro() {
        introShown = true
        findViewById<TextView>(R.id.progress).text = ""
        findViewById<TextView>(R.id.title).setText(R.string.setup_intro_title)
        findViewById<TextView>(R.id.explain).setText(R.string.setup_intro_text)
        findViewById<TextView>(R.id.hint).text = ""
        findViewById<TextView>(R.id.status).text = ""
        findViewById<Button>(R.id.open).visibility = View.GONE
        findViewById<Button>(R.id.skip).visibility = View.GONE
        findViewById<Button>(R.id.next).apply {
            setText(R.string.setup_intro_start)
            visibility = View.VISIBLE
        }
    }

    /** «Дальше»/«Начать» — единая главная кнопка, поведение зависит от экрана. */
    private fun onPrimary() {
        if (introShown) {
            introShown = false
            index = firstUnfinished()
            render()
        } else {
            advance()
        }
    }

    /** Первый ещё не выполненный шаг: уже пройденные (проверяемые) пропускаем. */
    private fun firstUnfinished(): Int {
        val done = donePrefs()
        for (i in steps.indices) {
            val step = steps[i]
            val ok = step.granted(this) == true || done.getBoolean(step.id, false)
            if (!ok) return i
        }
        return steps.size
    }

    override fun onResume() {
        super.onResume()
        // Вернулись из системных настроек — перепроверяем текущий шаг.
        if (index < steps.size) render()
    }

    private fun current() = steps[index]

    private fun render() {
        if (index >= steps.size) {
            finishWizard()
            return
        }
        val step = current()
        findViewById<TextView>(R.id.progress).text =
            getString(R.string.setup_step_progress, index + 1, steps.size)
        findViewById<TextView>(R.id.title).text = step.title
        findViewById<TextView>(R.id.explain).text = step.explain
        findViewById<TextView>(R.id.hint).text = step.hint

        val status = findViewById<TextView>(R.id.status)
        when (step.granted(this)) {
            true -> {
                status.setText(R.string.setup_granted)
                status.setTextColor(0xFF22C55E.toInt())
            }
            false -> {
                status.setText(R.string.setup_not_granted)
                status.setTextColor(0xFF888888.toInt())
            }
            null -> status.text = "" // автозапуск/закрепление проверить нельзя — подтверждает человек
        }

        // Если шаг проверяем и уже выполнен — прячем «Открыть», оставляем «Дальше».
        val alreadyDone = step.granted(this) == true
        findViewById<Button>(R.id.open).visibility = if (alreadyDone) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.skip).visibility =
            if (step.granted(this) == null) View.GONE else View.VISIBLE
    }

    private fun openCurrent() {
        val intent = current().intent(this) ?: return
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Конкретного экрана на этой прошивке нет — падать нельзя, просто идём дальше.
        }
    }

    private fun advance() {
        // Запоминаем, что шаг пройден (для проверяемых — по факту, для ручных — по кнопке).
        donePrefs().edit().putBoolean(current().id, true).apply()
        index += 1
        render()
    }

    private fun skipCurrent() {
        // Пропустить можно, но честно предупреждаем: помощь может работать нестабильно.
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.setup_skip_warn))
            .setNegativeButton(R.string.setup_skip_back, null)
            .setPositiveButton(R.string.setup_skip_anyway) { _, _ -> advance() }
            .show()
    }

    private fun donePrefs(): android.content.SharedPreferences =
        getSharedPreferences("pult_setup", Context.MODE_PRIVATE)

    private fun finishWizard() {
        setContentView(R.layout.activity_setup)
        findViewById<TextView>(R.id.progress).text = ""
        findViewById<TextView>(R.id.title).setText(R.string.setup_finished_title)
        findViewById<TextView>(R.id.explain).setText(R.string.setup_finished_text)
        findViewById<TextView>(R.id.hint).text = ""
        findViewById<TextView>(R.id.status).text = ""
        findViewById<Button>(R.id.open).visibility = View.GONE
        findViewById<Button>(R.id.skip).visibility = View.GONE
        findViewById<Button>(R.id.next).apply {
            setText(R.string.setup_check)
            setOnClickListener {
                PultService.start(this@SetupActivity)
                finish()
            }
        }
    }
}
