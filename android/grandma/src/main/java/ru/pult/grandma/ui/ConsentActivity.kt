package ru.pult.grandma.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ru.pult.grandma.R
import ru.pult.grandma.service.PultService

/**
 * «Петя хочет помочь» — единственное действие бабушки.
 *
 * Экран показывается поверх блокировки и включает дисплей: запрос, которого не видно,
 * бесполезен. Кнопка «Разрешить» занимает большую часть экрана, «Не сейчас» — мелкая
 * и без последствий (docs/android-grandma.md §1).
 *
 * Опции «разрешать всегда» здесь нет и не будет: постоянный доступ — это уже не помощь.
 */
class ConsentActivity : AppCompatActivity() {

    /**
     * Мы сами увели экран согласия в фон, открыв системный диалог захвата.
     * Без этого флага отказ по умолчанию из `onPause` сработал бы на собственный
     * системный диалог — и «Разрешить» превращалось бы в «Не сейчас».
     */
    private var awaitingCapturePermission = false
    private var autoMode = false

    private val captureRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Системный диалог захвата спрашиваем ПОСЛЕ понятного вопроса от нас,
        // иначе бабушка видит непонятное системное окно раньше своего.
        awaitingCapturePermission = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            PultService.grant(this, result.resultCode, result.data)
        } else {
            // Отказалась в системном диалоге — это тоже «не сейчас», а не ошибка.
            PultService.deny(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent)

        val peerName = intent.getStringExtra(EXTRA_PEER) ?: "Помощник"
        val note = intent.getStringExtra(EXTRA_NOTE)
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)

        findViewById<TextView>(R.id.title).text = getString(R.string.consent_title, peerName)
        findViewById<TextView>(R.id.note).apply {
            text = note.orEmpty()
            visibility = if (note.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        autoMode = auto
        val allow = findViewById<Button>(R.id.allow)
        val deny = findViewById<Button>(R.id.deny)

        if (auto) {
            // Турнкей-режим: бабушка ничего не нажимает. Но она ВИДИТ, кто подключается —
            // короткое неинтерактивное сообщение, потом показ начинается сам. Это и есть
            // граница легальности: не «тихо», а «без действий с её стороны».
            allow.text = getString(R.string.consent_auto, peerName)
            allow.isEnabled = false
            deny.visibility = View.GONE
            allow.postDelayed({ launchCapture() }, AUTO_NOTICE_MS)
        } else {
            allow.setOnClickListener { launchCapture() }
            deny.setOnClickListener {
                PultService.deny(this)
                finish()
            }
        }
    }

    private fun launchCapture() {
        // Авто-запуск приходит по таймеру: к этому моменту activity могла уже уйти
        // (замена singleTask, разворот, уход с экрана). Тогда launcher не зарегистрирован
        // и launch() падает — просто выходим, сессия начнётся с нового запроса.
        // Достаточно, чтобы activity была жива и зарегистрировала launcher (CREATED+).
        // Требовать RESUMED нельзя: на MIUI окно кратко теряет фокус, и запуск сорвался бы.
        if (isFinishing || isDestroyed) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        awaitingCapturePermission = true
        // При выданном appop PROJECT_MEDIA (Device Owner) система не показывает свой диалог
        // и сразу возвращает разрешение — бабушка его не видит.
        runCatching { captureRequest.launch(manager.createScreenCaptureIntent()) }
            .onFailure { awaitingCapturePermission = false; PultService.deny(this); finish() }
    }

    /**
     * Отказ по умолчанию (ручной режим): бабушка ушла с экрана → сессии нет.
     * НЕ применяется в авто-режиме (семья настроила автосогласие) и во время нашего
     * собственного системного диалога захвата — иначе на MIUI кратковременная потеря
     * фокуса ложно отменяла бы согласие.
     */
    override fun onPause() {
        super.onPause()
        if (isFinishing || awaitingCapturePermission || autoMode) return
        PultService.deny(this)
        finish()
    }

    companion object {
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_NOTE = "note"
        private const val EXTRA_AUTO = "auto"

        /** Сколько бабушка видит «Петя подключается», прежде чем показ начнётся сам. */
        private const val AUTO_NOTICE_MS = 2000L

        fun show(context: Context, peerName: String, note: String?, auto: Boolean) {
            context.startActivity(
                Intent(context, ConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_PEER, peerName)
                    .putExtra(EXTRA_NOTE, note)
                    .putExtra(EXTRA_AUTO, auto),
            )
        }
    }
}
