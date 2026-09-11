package ru.pult.grandma.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Одноразовый запрос разрешения на уведомления (Android 13+).
 *
 * Зачем: full-screen intent подъёма BankID по диплинку (Notifications.bankIdLaunch)
 * без POST_NOTIFICATIONS молча не показывается — а телефон почти всегда спит запертым
 * (enforceLocked), поэтому остаются только direct start (блокирует keyguard/BAL) и
 * shell (в покое сессии нет). Итог — bankid-open-failed на каждый диплинк
 * (поймано на стенде 2026-09-11). Запрос показываем редко: максимум раз в сутки,
 * пока разрешение не выдано.
 */
class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish() // отказ не караем: FSI-путь просто остаётся недоступен, как раньше
    }

    companion object {
        private const val REQ = 1
    }
}
