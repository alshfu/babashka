package ru.pult.grandma.session

import android.content.Intent
import ru.pult.core.protocol.IceServer

/**
 * Транспорт экрана — WebRTC-часть, вынесенная за интерфейс.
 *
 * Причина не в «чистой архитектуре», а в проверяемости: `SessionController` содержит
 * правила доступа (согласие → проверка пары → показ), и их нужно уметь тестировать
 * без libwebrtc и без реального устройства.
 *
 * Ключевое требование к любой реализации: соединение поднимается **без видеотрека**,
 * а реальный трек подставляется в уже согласованный трансивер только из `startScreen`.
 * Иначе кадры пойдут раньше, чем подтверждена личность помощника.
 */
interface ScreenTransport {

    /** Создать соединение и ответить на offer. Возвращает SDP ответа — захват при этом не идёт. */
    suspend fun answer(offerSdp: String, iceServers: List<IceServer>): String

    /** Локальный отпечаток DTLS — входит в транскрипт MAC. Доступен после `answer`. */
    val localFingerprint: String

    suspend fun addIceCandidate(candidateJson: String)

    /**
     * Запуск показа. Вызывается ТОЛЬКО из состояния CONNECTED, то есть после согласия
     * бабушки и успешной проверки MAC помощника.
     *
     * `screenCapturePermission` — Intent из системного диалога захвата: libwebrtc
     * создаёт `MediaProjection` сам, внутри капчурера. Если проекция уже поднята
     * (`hasCapture()`), можно передать `null` — трек переиспользуется без нового диалога.
     * Это критично на MIUI: повторный системный диалог из фона прошивка блокирует, и без
     * переиспользования вторая (и любая последующая) сессия бы не поднималась.
     */
    suspend fun startScreen(screenCapturePermission: Intent?)

    /** Жив ли конвейер захвата (проекция получена и не освобождена). */
    fun hasCapture(): Boolean

    /** Полностью освободить захват и проекцию. Вызывается при остановке сервиса, не сессии. */
    fun releaseCapture()

    /** Гашение: трек снимается с отправителя, соединение остаётся. Решает устройство бабушки. */
    suspend fun setRedacted(on: Boolean, reason: String, packageName: String? = null)

    fun sendControl(payload: String)

    fun close()

    /** ICE-кандидаты наружу — их отправляет контроллер сессии. */
    fun setIceCandidateListener(listener: (String) -> Unit)

    /** Управляющие сообщения от помощника (указатель, подписи, стоп). */
    fun setControlListener(listener: (String) -> Unit)

    /** Показ прекратился со стороны системы (бабушка нажала «Остановить» в шторке Android). */
    fun setScreenStoppedListener(listener: () -> Unit)
}
