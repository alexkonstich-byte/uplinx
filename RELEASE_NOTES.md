## MyProxy 1.2.0

**Скачать:** `MyProxy-1.2.0-setup.exe` (установщик), `MyProxy-1.2.0.msi` или `MyProxy-1.2.0-portable-win64.zip` (портативная версия).
Если уже стоит 1.1.0, приложение само предложит обновиться.

### Material You везде
- **Живой фон.** Крупные фигуры M3 Expressive (cookie, clover, burst, flower) медленно вращаются, перетекают одна в другую и плавают. При переходе между экранами они сдвигаются с параллаксом. Цвета берутся из динамической палитры.
- **Кнопка подключения вращается всегда.** При подключении она ускоряется, вокруг по орбите летают фигурки, а каждая смена состояния добавляет пружинистый доворот.
- **Шапка окна Windows** окрашена в цвета темы, следует за светлым и тёмным режимом и меняется на лету (Windows 11).
- **Флаги стран** стоят крупно на месте значка протокола и в карточке сервера на главной.

### English
- Интерфейс полностью на английском. Язык выбирается в «Настройки → Оформление» (системный, русский или английский) и переключается на лету.

### Подписки, которые «не поддерживают приложение»
- Если панель отдала заглушку вроде «App not supported» (`0.0.0.0:1`), MyProxy сам перебирает клиенты (Happ, v2rayN, sing-box, Clash) и запоминает подходящий для этой подписки.
- В запросы добавлены заголовки устройства (`x-hwid`, `x-device-os`, `x-ver-os`, `x-device-model`). Они нужны панелям с лимитом устройств.

### Обновления из GitHub Releases
- Проверка при запуске и затем каждые 6 часов.
- Перед обновлением показывается окно с полным списком изменений; обновление ставится в один клик с прогрессом.
- **Портативная версия** обновляет себя из zip-архива, установщик для этого не нужен.
- После обновления открывается экран «Что нового».

---

## MyProxy 1.2.0 (English)

- **Material You everywhere:**
  - a living backdrop of M3 Expressive shapes that turn, morph and drift, with parallax between screens;
  - an always-spinning connect button with orbiting shapes;
  - a themed Windows 11 title bar;
  - flag avatars.
- **English UI**, switchable live in Settings.
- **Auto client detection** for panels that answer "App not supported", plus device headers for HWID-limited panels.
- **Updates from GitHub Releases:**
  - checks every 6 hours;
  - full release notes before updating;
  - self-update for the portable build;
  - a "What's new" screen after updating.
