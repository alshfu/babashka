package ru.pult.grandma.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import ru.pult.grandma.R

/**
 * Оверлей сессии: тонкая зелёная рамка вокруг экрана + указатель. И только.
 *
 * Окно passthrough — не перехватывает касания, бабушка спокойно пользуется телефоном,
 * включая кнопки навигации внизу. Сообщение «кто управляет» и кнопка «Стоп» — в уведомлении
 * у часов, не заслоняя экран.
 */
class SessionOverlay(private val context: Context) {

    private val windows = context.getSystemService(WindowManager::class.java)
    private var frameWindow: FrameLayout? = null
    private var pointer: View? = null
    private var slideWindow: FrameLayout? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Обучающий слайд: крупная подготовленная картинка-инструкция поверх экрана + подпись.
     * Внук листает слайды со своей панели, бабушка смотрит демонстрацию. Окно ловит касания
     * (не passthrough) — чтобы во время показа инструкции бабушка случайно не нажала своё
     * приложение под слайдом; когда внук скроет слайд, она действует уже сама.
     */
    fun showSlide(bitmap: android.graphics.Bitmap, caption: String?) {
        if (!canDraw()) return
        hideSlide()
        val root = FrameLayout(context).apply { setBackgroundColor(Color.parseColor("#EE000000")) }

        val image = android.widget.ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        root.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))

        if (!caption.isNullOrBlank()) {
            val label = android.widget.TextView(context).apply {
                text = caption
                setTextColor(Color.WHITE)
                textSize = 20f
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply { setColor(Color.parseColor("#CC22C55E")) }
            }
            root.addView(
                label,
                FrameLayout.LayoutParams(WRAP, WRAP).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(28)
                },
            )
        }
        windows.addView(root, blockingParams())
        slideWindow = root
    }

    fun hideSlide() {
        slideWindow?.let(windows::removeView)
        slideWindow = null
    }

    /**
     * По согласованному дизайну оверлей — это ТОЛЬКО тонкая зелёная рамка вокруг экрана
     * и указатель. Сообщение «кто управляет» и кнопка «Стоп» живут в уведомлении у часов,
     * не заслоняя экран. Никаких больших кнопок и баннеров поверх — чисто и спокойно.
     */
    fun show(peerName: String, onStop: () -> Unit) {
        // BankID и другие банковские приложения отказываются работать при наличии
        // любого системного оверлея (даже passthrough-рамки). Поэтому визуальную рамку
        // не показываем вовсе — статус сессии остаётся в уведомлении у часов.
        // Если потребуется вернуть рамку, добавить проверку «на экране нет банков».
        return
    }

    /**
     * Индикатор записи сценария. Красная рамка + метка «● ЗАПИСЬ» — чтобы бабушка видела,
     * что идёт запись действий, а не показ. В этом режиме экран НЕ снимается вовсе:
     * рамка здесь — знак записи, а не трансляции.
     */
    fun showRecording() {
        if (frameWindow != null) return
        if (!canDraw()) return

        val red = Color.parseColor("#EF4444")
        val root = FrameLayout(context)

        val frame = View(context).apply {
            background = GradientDrawable().apply {
                setStroke(dp(2), red)
                setColor(Color.TRANSPARENT)
            }
        }
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))

        val label = android.widget.TextView(context).apply {
            text = "● ЗАПИСЬ"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(red)
                cornerRadius = dp(12).toFloat()
            }
        }
        root.addView(
            label,
            FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            },
        )

        windows.addView(root, passthroughParams())
        frameWindow = root
    }

    fun movePointer(fractionX: Float, fractionY: Float) {
        val root = frameWindow ?: return
        val dot = pointer ?: return
        // Центрируем по ФИКСИРОВАННОМУ размеру, а не dot.width: в момент первого показа вью
        // ещё не измерена (width == 0), и указатель ставился углом — отсюда «кривой курсор»,
        // смещённый вправо-вниз на полразмера.
        val half = dp(POINTER_DP) / 2f
        dot.x = fractionX * root.width - half
        dot.y = fractionY * root.height - half
        dot.visibility = View.VISIBLE
        dot.removeCallbacks(hidePointer)
        dot.postDelayed(hidePointer, POINTER_TTL_MS)
    }

    private val hidePointer = Runnable { pointer?.visibility = View.GONE }

    fun hide() {
        frameWindow?.let(windows::removeView)
        frameWindow = null
        pointer = null
        hideSlide()
    }

    /** Рамка и указатель: видно, но касания проходят насквозь (навигация внизу работает). */
    private fun passthroughParams() = WindowManager.LayoutParams(
        MATCH, MATCH,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    /** Слайд перекрывает экран и ЛОВИТ касания (не passthrough): бабушка смотрит, не мажет. */
    private fun blockingParams() = WindowManager.LayoutParams(
        MATCH, MATCH,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Не FOCUSABLE (кнопки Назад/Домой у бабушки работают), но касания ловит.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun dp(value: Int): Int =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private companion object {
        const val POINTER_TTL_MS = 3000L
        const val POINTER_DP = 56
        const val MATCH = WindowManager.LayoutParams.MATCH_PARENT
        const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT
    }
}
