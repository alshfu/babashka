package ru.pult.grandma.calls

import android.telecom.Call
import android.telecom.CallScreeningService
import ru.pult.core.calls.PhoneNumbers

/**
 * Фильтр звонков (ТЗ п.7).
 *
 * Мошенник, звонящий «под видом внука», просто не дозванивается. Решение принимается
 * локально и за миллисекунды: никаких сетевых запросов здесь быть не может — система
 * даёт на ответ считаные секунды, а номера бабушки мы никуда не отправляем.
 *
 * `READ_CALL_LOG` не запрашивается: `CallScreeningService` в нём не нуждается.
 */
class PultCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart
        val whitelist = Whitelist(applicationContext)

        // Экстренные номера проходят при любых настройках — блокировать их нельзя.
        if (PhoneNumbers.isEmergency(number)) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }

        if (whitelist.shouldAllow(number)) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }

        respondToCall(
            details,
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                // Журнал системы не подменяем: бабушка должна видеть, что звонок был,
                // и одним нажатием добавить номер в свои, если он оказался нужным.
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build(),
        )

        // TODO(V1): уведомление «Заблокирован звонок с номера …, нажмите, если это свой»
        // с добавлением в белый список одним нажатием (docs/call-screening.md §4).
    }
}
