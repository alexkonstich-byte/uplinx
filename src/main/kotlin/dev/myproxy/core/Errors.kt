package dev.myproxy.core

/** A failure the user can understand, with the raw text kept for "details". */
data class FriendlyError(val title: String, val hint: String, val details: String)

object Errors {
    fun explain(raw: String): FriendlyError {
        val t = raw.lowercase()
        fun e(title: String, hint: String) = FriendlyError(title, hint, raw.trim())
        return when {
            "only one usage of each socket address" in t || "address already in use" in t || "bind:" in t ->
                e("Порт уже занят", "Другая программа использует порт прокси. Смените порты SOCKS/HTTP в настройках или закройте другой VPN-клиент.")
            "wintun" in t || "access is denied" in t || "access denied" in t ->
                e("Не удалось создать TUN-адаптер", "Нужны права администратора, и wintun.dll должен лежать рядом с xray.exe. Перезапустите MyProxy от администратора.")
            "allowinsecure" in t ->
                e("Устаревший параметр allowInsecure", "Xray больше не поддерживает отключение проверки сертификата. Укажите SHA-256 сертификата сервера (pinnedPeerCertSha256).")
            "geosite" in t && ("not found" in t || "failed to load" in t) || "geoip" in t && "not found" in t ->
                e("Нет нужной гео-базы", "Правило ссылается на категорию, которой нет в текущих гео-базах. Обновите гео-базы или смените источник в настройках.")
            "failed to parse" in t || "invalid character" in t || "unknown field" in t || "infra/conf" in t ->
                e("Ошибка в конфигурации сервера", "Ссылка или конфиг содержит то, что Xray не принимает. Проверьте сервер в редакторе или откройте детали.")
            "reality" in t && ("verify" in t || "public key" in t || "publickey" in t) ->
                e("Ошибка REALITY", "Не совпадает публичный ключ или shortId. Обновите подписку или проверьте параметры сервера.")
            "x509" in t || "certificate" in t ->
                e("Ошибка сертификата", "Сервер предъявил сертификат, который не прошёл проверку. Проверьте SNI или укажите SHA-256 сертификата.")
            "timeout" in t || "i/o timeout" in t || "deadline exceeded" in t ->
                e("Сервер не отвечает", "Сервер недоступен или блокируется. Попробуйте другой сервер или транспорт.")
            "connection refused" in t ->
                e("Сервер отклонил соединение", "На этом порту ничего не слушает. Возможно, сервер выключен или поменял порт — обновите подписку.")
            "no such host" in t || "lookup" in t ->
                e("Не найден адрес сервера", "DNS не смог найти сервер. Проверьте интернет или адрес в настройках сервера.")
            "amneziawg" in t ->
                e("Ошибка AmneziaWG", "Туннель AmneziaWG не запустился. Проверьте конфиг и что клиент AmneziaWG установлен.")
            "github" in t || "http 4" in t || "http 5" in t ->
                e("Не удалось скачать", "Сервер загрузки вернул ошибку. Проверьте интернет и попробуйте позже.")
            else -> e("Не удалось подключиться", raw.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "Неизвестная ошибка")
        }
    }
}
