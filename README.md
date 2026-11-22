# MyProxy

Прокси-клиент для Windows в стиле **Material You 3 Expressive**. Работает на ядре **Xray-core**.
Подписки, ссылки, ключи Amnezia, WireGuard и AmneziaWG, системный прокси и TUN.

<p>
  <img src="docs/screenshots/home-light.png" width="49%">
  <img src="docs/screenshots/home-dark.png" width="49%">
</p>
<p>
  <img src="docs/screenshots/servers.png" width="49%">
  <img src="docs/screenshots/routing-dark.png" width="49%">
</p>
<p>
  <img src="docs/screenshots/subscriptions.png" width="49%">
  <img src="docs/screenshots/settings.png" width="49%">
</p>

## Возможности

**Импорт всего подряд.** Поддерживаются:
- ссылки `vless://`, `vmess://`, `trojan://`, `ss://`, `socks://`, `http(s)://`, `hy2://` / `hysteria2://`, `wireguard://`;
- ключи **Amnezia** `vpn://`: из них берутся контейнеры AWG, WireGuard и Xray;
- файлы `.conf` WireGuard и **AmneziaWG**;
- **JSON-конфиги Xray**, одиночные и массивом (в таком формате панели вроде Remnawave отдают подписки Happ);
- подписки в base64 или обычным списком.

Импортировать можно через Ctrl+V в любом месте окна, перетаскиванием файлов в окно, через «Открыть файл…» или по диплинку `myproxy://add/<ссылка>`.

**Подписки.** Учитываются заголовки `subscription-userinfo` (трафик и срок действия), `profile-title`, `profile-update-interval`, `support-url` и `announce`. Подписки обновляются автоматически, а при обновлении выбранный сервер сохраняется.

**Режимы.**
- *Системный прокси* (WinINet). Прежние настройки прокси сохраняются и восстанавливаются даже после падения приложения.
- *TUN* через встроенный `tun` inbound Xray (wintun) с `autoSystemRoutingTable` и WFP-защитой от утечек DNS.

**Маршрутизация.** Три режима: по правилам, глобальный, напрямую. Есть пресеты: блок рекламы, РФ напрямую, «через прокси только заблокированное» на гео-базах runetfreedom. Свои правила задаются по домену, IP, geosite, geoip, **процессу** или порту.

**AmneziaWG.** Конфиги только с Jc/Jmin/Jmax работают прямо в Xray (маска `noise`), с маршрутизацией. Полный AWG с S1/S2/H1–H4 запускается через официальный движок AmneziaWG как туннельная служба.

**Пинг.** TCP-пинг всех серверов или группы, реальная задержка через туннель, сортировка по пингу.

**Остальное.** QR-коды, экспорт ссылок и `.conf`, просмотр сгенерированного конфига, редактор сервера, логи ядра с фильтром, скорость и график в реальном времени, трей, автозапуск.

**Material You.** Палитра строится из акцентного цвета Windows (MaterialKolor) и меняется на лету. Можно выбрать свой цвет, один из 9 стилей палитры и AMOLED-тему. Используются компоненты M3 Expressive: морфинг фигур, LoadingIndicator, волнистые прогресс-бары, ButtonGroup, MotionScheme.expressive().

## Сборка

Нужен JDK 25.

```
gradlew run                  # запустить
gradlew test                 # тесты парсеров и генератора конфига
gradlew packageExe           # установщик .exe (packageMsi — .msi)
```

Ядро Xray, гео-базы и wintun скачиваются при первом подключении в `%APPDATA%\MyProxy\core`.
Для TUN и AmneziaWG нужны права администратора: приложение само предложит перезапуститься с ними.

Скриншоты рендерятся офскрин на демо-данных, без окна и без подключения:

```
gradlew screenshots -Dmyproxy.home=<временная папка> -Dshots.out=<папка>
```

## Стек

Kotlin, Compose Multiplatform Desktop, Material 3 (Expressive), MaterialKolor, kotlinx.serialization, JNA, ZXing, Xray-core.
