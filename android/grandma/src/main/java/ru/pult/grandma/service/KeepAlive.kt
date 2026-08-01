package ru.pult.grandma.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * Удержание телефона «на связи» при выключенном экране.
 *
 * Это ядро выживания на MIUI/HyperOS (99% телефонов пожилых людей). Без этого при
 * гаснущем экране на батарее система отключает радио Wi-Fi и замораживает процесс —
 * сокет к сигналингу умирает, и запрос помощи до бабушки не доходит.
 *
 *  · WifiLock (HIGH_PERF/LOW_LATENCY) — не даёт Wi-Fi уснуть.
 *  · Partial WakeLock — держит CPU, чтобы жил цикл переподключения и сам сокет.
 *
 * Оба берутся на всё время готовности телефона. Для устройства бабушки, стоящего на
 * зарядке/подставке, это нормально; расход батареи — осознанная плата за «всегда готов».
 */
class KeepAlive(context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        if (wakeLock?.isHeld == true) return

        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pult:keepalive").apply {
            setReferenceCounted(false)
            acquire()
        }

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi?.createWifiLock(mode, "pult:keepalive")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }
}
