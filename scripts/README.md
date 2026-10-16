# Диагностические и подготовительные скрипты

Здесь будут находиться воспроизводимые скрипты для подготовки Windows и
неразрушающей диагностики телефона. Скрипты должны проверять предусловия,
останавливаться при ошибках и не выполнять прошивку или сброс устройства.

- `install-platform-tools.ps1` — локально устанавливает Android Platform Tools.
- `start-lg-adb.ps1` — перезапускает ADB с `ADB_USB_LEGACY=1` для проверки
  обрывов LG на новом USB backend Platform Tools 37. Откат:
  `start-lg-adb.ps1 -DefaultUsbBackend`. Переменные задаются только процессу,
  системные настройки Windows и телефона не меняются.
- `get-lg-usb-driver.ps1` — получает LG Mobile Driver и проверяет подпись.
- `collect-diagnostics.ps1` — собирает очищенный отчёт об устройстве.
- `download-termux-apks.ps1` — загружает закреплённые стабильные APK Termux и
  Termux:Boot из официальных GitHub Releases и проверяет официальные SHA-256.
- `VerifyApk.java` — проверяет APK современными схемами подписи через
  официальную библиотеку Google `apksig` и выводит fingerprint сертификата.
- `install-termux-apks.ps1` — повторно проверяет закреплённые SHA-256 и
  устанавливает Termux и Termux:Boot через авторизованный ADB.
- `uninstall-termux.ps1` — удаляет сначала плагин, затем Termux; требует явный
  флаг `-ConfirmRemoval`, потому что удаление стирает данные приложения.
- `export-phone-logs.ps1` — копирует журналы установки, запуска и runtime из
  приватных каталогов Termux/Ubuntu в `logs/phone`; временные ADB-файлы после
  каждой копии удаляются.
- `build-voice-color-station.ps1` — без Gradle собирает и подписывает локальным
  debug-ключом Android APK локальной голосовой цветовой станции.
- `prepare-vosk-dependencies.ps1` — скачивает закреплённые Vosk/JNA, русскую
  модель и совместимый R8/D8, проверяет SHA-256 и готовит assets/ARM64 `.so`.
- `prepare-wakeword-dependencies.ps1` — проверяет SHA-256 ONNX Runtime Android,
  feature-моделей openWakeWord и обученного «Помогатора», готовит assets/ARM64.
- `verify-wakeword-runtime.ps1` — проверяет self-test трёх ONNX-моделей, READY,
  foreground-службу, микрофон, память и ошибки только текущего процесса.
- `train-personal-wakeword.ps1` — локальные CPU-этапы `prepare`/`fit` для
  проверенных личных записей; аугментация, frozen ONNX-признаки, дообучение
  классификатора. Голос/модель остаются в ignored `private/`, логи — в `logs/`.
- `install-voice-color-station.ps1` — устанавливает собранный APK через ADB и
  открывает приложение; разрешение на микрофон остаётся за пользователем.
- `verify-vosk-runtime.ps1` — сохраняет версию/разрешения APK, состояние
  foreground-service, AppOps микрофона, уведомление и релевантный logcat.

