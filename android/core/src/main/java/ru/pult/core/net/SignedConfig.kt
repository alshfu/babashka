package ru.pult.core.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Проверка ПОДПИСАННОГО конфига из внешнего источника (GitHub raw / CDN).
 *
 * Смысл: «скажем приложению, где брать связь» — но только если это сказали МЫ. Источник может
 * быть публичным и даже скомпрометированным: без приватного ключа (он офлайн у команды) валидную
 * подпись не подделать, а без валидной подписи манифест отвергается. Это единственное, что
 * отличает «удобное обновление адресов» от «малвари, тянущей чужой конфиг».
 *
 * Формат конверта: {"payload": base64(json-манифеста), "sig": base64(ECDSA P-256 SHA-256)}.
 * Подпись считается по ТОЧНЫМ байтам payload — поэтому canonicalization не нужна.
 */
object SignedConfig {

    @Serializable
    data class Manifest(
        val version: Int = 1,
        val endpoints: List<String> = emptyList(),
        val tunnelUri: String? = null,
        val updatedAt: Long = 0,
    )

    @Serializable
    private data class Envelope(val payload: String, val sig: String)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Вернуть манифест, ТОЛЬКО если подпись сходится с запиненным публичным ключом.
     * Любая ошибка (битый конверт, чужая подпись) → null: молча ничего не применяем.
     */
    fun verify(envelopeJson: String, pinnedPublicKeyB64: String): Manifest? = runCatching {
        val env = json.decodeFromString<Envelope>(envelopeJson)
        val payload = Base64.getDecoder().decode(env.payload)
        val sig = Base64.getDecoder().decode(env.sig)

        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pinnedPublicKeyB64)))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload)
            verify(sig)
        }
        if (!ok) return null
        json.decodeFromString<Manifest>(String(payload, Charsets.UTF_8))
    }.getOrNull()
}
