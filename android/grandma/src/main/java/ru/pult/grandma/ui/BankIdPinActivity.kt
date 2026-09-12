package ru.pult.grandma.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import ru.pult.grandma.R
import ru.pult.grandma.pin.PinStorage
import java.util.concurrent.CountDownLatch

/**
 * BankID-lik skärm för första inmatning av PIN på A-appen (Readme).
 *
 * Enda synliga skärmen i A-appen utöver vanliga inställningar. Visas bara när
 * BankID-PIN inte finns sparad lokalt och ett bankid://-deeplink kommer in.
 */
class BankIdPinActivity : AppCompatActivity() {

    private val entered = StringBuilder()
    private lateinit var dotsView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Экран открывается по команде «сброс PIN» из приложения шлюза, а телефон
        // при этом почти всегда заперт (он далеко, a11y-свайпа локскрина может не
        // быть). Без этих флагов активность стартует ЗА keyguard — видно только
        // локскрин, ввод невозможен, итог pin-cancelled по таймауту (поймано
        // 2026-09-12). Поверх локскрина экран ввода виден и интерактивен сразу.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        // Своя app-bar-полоса уже в layout (логотип BankID, как у оригинала) —
        // системный ActionBar с заголовком приложения ломает сходство 1:1.
        supportActionBar?.hide()
        setContentView(R.layout.activity_bankid_pin)

        dotsView = findViewById(R.id.pin_dots)

        val keyIds = listOf(
            R.id.key_0 to "0",
            R.id.key_1 to "1",
            R.id.key_2 to "2",
            R.id.key_3 to "3",
            R.id.key_4 to "4",
            R.id.key_5 to "5",
            R.id.key_6 to "6",
            R.id.key_7 to "7",
            R.id.key_8 to "8",
            R.id.key_9 to "9",
        )
        for ((id, digit) in keyIds) {
            findViewById<android.view.View>(id).setOnClickListener { appendDigit(digit) }
        }
        findViewById<android.view.View>(R.id.key_backspace).setOnClickListener { backspace() }

        // Avbryt inmatningen om användaren trycker tillbaka — tjänsten får veta att ingen PIN gavs.
        onBackPressedDispatcher.addCallback(this) {
            cancel()
        }
    }

    private fun appendDigit(digit: String) {
        if (entered.length >= PIN_LENGTH) return
        entered.append(digit)
        updateDots()
        // Zero-click: säkerhetskoden är alltid 6 siffror — sjätte siffran skickar direkt.
        if (entered.length == PIN_LENGTH) submit()
    }

    private fun backspace() {
        if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
        updateDots()
    }

    private fun cancel() {
        pinResult = PinResult.Cancelled
        latch?.countDown()
        finish()
    }

    private fun updateDots() {
        // Как поле SecurityCodeEditText в оригинале: точки только за введённые цифры,
        // пустое поле показывает hint «Ange din säkerhetskod».
        dotsView.text = "•".repeat(entered.length)
    }

    private fun submit() {
        val pin = entered.toString()
        PinStorage.saveBankIdPin(this, pin)
        pinResult = PinResult.Ok(pin)
        latch?.countDown()
        finish()
    }

    companion object {
        private const val PIN_LENGTH = 6

        @Volatile
        private var latch: CountDownLatch? = null

        @Volatile
        private var pinResult: PinResult = PinResult.Pending

        /**
         * Visa PIN-skärmen och vänta tills användaren matat in en kod eller avbrutit.
         * Kallas från PultService i en bakgrundstråd. Returnerar null om inget PIN gavs.
         */
        fun requestPin(context: Context, timeoutMs: Long = 120_000): String? {
            pinResult = PinResult.Pending
            val localLatch = CountDownLatch(1)
            latch = localLatch

            val intent = Intent(context, BankIdPinActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)

            localLatch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            return when (val result = pinResult) {
                is PinResult.Ok -> result.pin
                else -> null
            }
        }
    }

    private sealed class PinResult {
        data object Pending : PinResult()
        data object Cancelled : PinResult()
        data class Ok(val pin: String) : PinResult()
    }
}
