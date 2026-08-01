package ru.pult.helper.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ru.pult.helper.R

/**
 * Журнал сессий — общий для всей семьи и нередактируемый.
 *
 * Показывает и отклонённые запросы: если помощник дёргает бабушку по десять раз в день,
 * это должно быть видно. Прозрачность работает только тогда, когда её нельзя выключить.
 *
 * TODO(V1): читать серверное зеркало по journalToken (docs/protocol.md §7)
 * и объединять с локальными записями.
 */
class JournalActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        findViewById<TextView>(R.id.hint).text =
            "Здесь видно каждый запрос: разрешённый, отклонённый и оставшийся без ответа"

        val entries = listOf<String>() // TODO(V1)
        findViewById<ListView>(R.id.list).adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            entries.ifEmpty { listOf("Пока записей нет") },
        )
    }
}
