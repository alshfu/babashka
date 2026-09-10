package ru.pult.grandma.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ru.pult.core.pairing.EncryptedPairStore
import ru.pult.core.pairing.PairingPacket
import ru.pult.grandma.BuildConfig
import ru.pult.grandma.R
import ru.pult.grandma.pairing.PairingHost
import ru.pult.grandma.service.PultService

/**
 * Привязка к пульту: одноразовый QR с этого экрана сканирует помощник при личной
 * встрече (docs/pairing.md). Код живёт полторы минуты, потом показываем новый.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var host: PairingHost
    private var completed = false

    private val refresher = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            showQr()
            refresher.postDelayed(this, PairingPacket.QR_TTL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        host = PairingHost(EncryptedPairStore(this))
        showQr()
        refresher.postDelayed(refresh, PairingPacket.QR_TTL_MS)

        findViewById<Button>(R.id.scanned).setOnClickListener {
            val record = host.complete()
            if (record == null) {
                // Код устарел раньше, чем его отсканировали, — показываем свежий.
                Toast.makeText(this, R.string.pairing_expired, Toast.LENGTH_SHORT).show()
                showQr()
            } else {
                completed = true
                PultService.pairChanged(this)
                finish()
            }
        }
    }

    override fun onDestroy() {
        refresher.removeCallbacks(refresh)
        // Неподтверждённая пара не должна оседать в хранилище: секрет затираем.
        if (!completed) host.cancel()
        super.onDestroy()
    }

    private fun showQr() {
        val packet = host.start(
            peerName = getString(R.string.pairing_peer_default),
            signalingUrl = BuildConfig.DEFAULT_SIGNALING_URL,
        )
        findViewById<ImageView>(R.id.qr).setImageBitmap(host.qrBitmap(packet))
    }
}
