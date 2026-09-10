package ru.pult.grandma.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import ru.pult.grandma.service.PultService

/**
 * Системное согласие на захват экрана — единственный диалог, который вообще может
 * появиться. Своего UI у activity нет: это невидимый трамплин к системному диалогу
 * `MediaProjection` (песочница: запросы спаренного помощника принимаются автоматически).
 *
 * Нужна один раз: проекция живёт между сессиями (PultService), поэтому дальше показ
 * начинается молча. При выданном заранее appop PROJECT_MEDIA (выставляется при настройке
 * устройства) система не показывает и свой диалог — разрешение возвращается сразу.
 */
class ConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { startActivityForResult(manager.createScreenCaptureIntent(), REQ) }
            .onFailure { PultService.deny(this); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            if (resultCode == RESULT_OK && data != null) {
                PultService.grant(this, resultCode, data)
            } else {
                // Отказ в системном диалоге — это «не сейчас», а не ошибка.
                PultService.deny(this)
            }
        }
        finish()
    }

    companion object {
        private const val REQ = 7002

        /** Запросить системное согласие на захват (первый запуск, проекции ещё нет). */
        fun requestCapture(context: Context) {
            context.startActivity(
                Intent(context, ConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
