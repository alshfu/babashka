package ru.pult.helper.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.pult.helper.R

/**
 * Спаривание на стороне помощника: NFC-чтение или сканирование QR с экрана бабушки —
 * и только при личной встрече (docs/pairing.md).
 *
 * Ввода кода «со слов» здесь нет и не появится: любой такой путь воспроизводит атаку,
 * от которой продукт защищает.
 *
 * TODO(V1): чтение пакета через NFC (IsoDep) и сканер QR через CameraX,
 * затем PairingPacket.decode() и сохранение в EncryptedPairStore.
 */
class PairingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        findViewById<TextView>(R.id.hint).text =
            "Пара создаётся только при встрече: приложите телефоны (NFC) " +
                "или отсканируйте код с экрана бабушки"

        findViewById<ListView>(R.id.list).adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            listOf(
                "1. Встретиться лично",
                "2. На телефоне бабушки открыть «Настройка помощника»",
                "3. Приложить телефоны или отсканировать QR",
                "4. Проверить связь тут же, на месте",
            ),
        )

        findViewById<Button>(R.id.action).apply {
            text = "Сканировать код"
            setOnClickListener { /* TODO(V1): CameraX + PairingPacket.decode */ }
        }
    }
}
