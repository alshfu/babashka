package ru.pult.grandma.ui

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle

/**
 * Невидимая активность снятия блокировки: поднимает экран поверх keyguard и просит
 * его отступить, после чего сразу умирает. Требуется [ScreenUnlock] при старте
 * трансляции/управления: телефон далеко, а чёрный локскрин в эфире бесполезен.
 */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            runCatching {
                getSystemService(KeyguardManager::class.java)
                    ?.requestDismissKeyguard(this, null)
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        // Дело сделано (флаги применены при создании окна) — не оседаем на экране.
        finish()
    }
}
