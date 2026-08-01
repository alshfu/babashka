package ru.pult.grandma.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import ru.pult.core.pairing.PairRecord
import ru.pult.core.pairing.PairStore
import ru.pult.core.pairing.PairingPacket
import ru.pult.core.protocol.PairAuth

/**
 * Создание пары на телефоне бабушки (docs/pairing.md).
 *
 * Секрет рождается здесь и уезжает к помощнику ТОЛЬКО вблизи: NFC или QR с экрана.
 * Ни ссылки, ни кода по SMS, ни спаривания через сервер — всё это воспроизводило бы
 * атаку «внук в беде, продиктуйте код».
 *
 * QR одноразовый и живёт 90 секунд: сфотографировать «на потом» бесполезно.
 */
class PairingHost(private val store: PairStore) {

    private var pending: Pending? = null

    private data class Pending(val packet: PairingPacket, val record: PairRecord, val createdAt: Long)

    fun start(peerName: String, signalingUrl: String, now: Long = System.currentTimeMillis()): PairingPacket {
        val pairId = PairAuth.newId(16)
        val secret = PairAuth.newSecret()
        val packet = PairingPacket.create(pairId, secret, peerName, signalingUrl)
        val record = PairRecord(
            pairId = pairId,
            secret = secret,
            peerName = peerName,
            signalingUrl = signalingUrl,
            createdAt = now,
        )
        pending = Pending(packet, record, now)
        return packet
    }

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        val started = pending?.createdAt ?: return true
        return now - started > PairingPacket.QR_TTL_MS
    }

    /**
     * Подтверждение, что помощник действительно принял пару (он проверяет связь при встрече).
     * Только после этого старый секрет заменяется новым — иначе неудачное спаривание
     * оставило бы бабушку вообще без помощника.
     */
    fun complete(now: Long = System.currentTimeMillis()): PairRecord? {
        val current = pending ?: return null
        if (isExpired(now)) {
            cancel()
            return null
        }
        store.save(current.record)
        pending = null
        return current.record
    }

    fun cancel() {
        pending?.record?.secret?.fill(0)
        pending = null
    }

    fun qrBitmap(packet: PairingPacket, sizePx: Int = 720): Bitmap {
        val matrix = QRCodeWriter().encode(packet.encode(), BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
