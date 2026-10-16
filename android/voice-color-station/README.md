# Voice Color Station 0.6.0

Локальный голосовой POC для LG V50S на Android 12. Foreground-service слушает
микрофон через один `AudioRecord`, openWakeWord и Vosk без сети. Выполняются
только точные отдельные слова поддерживаемых цветов. Команда может
быть одной фразой — `Эй, Помогатор, включи синий` — или двумя: `Эй, Помогатор`, затем цвет
в течение шести секунд.

Поддерживаются красный, зелёный, голубой, синий, жёлтый, оранжевый,
фиолетовый, розовый, белый, серый и чёрный. Модуль `WakeWordDetector` непрерывно
выполняет PCM → mel → embedding → `pomogator.onnx`. При достижении порога он открывает
шестисекундное окно Vosk. Старые фонетические aliases удалены; до триггера Vosk
не декодирует поток. UI показывает score, срабатывание и текст команды.

У детектора 2,56 секунды прогрева после запуска и завершения команды.
Для первой проверки: дождаться прогрева, сказать «Эй, Помогатор», увидеть
«Эй, Помогатор услышан», затем сказать «включи синий». Профиль `personal-v2`
использует порог 0.65, зафиксированный до test; `generic` — прежний порог 0.50.
Срабатывание — акустическая оценка, а не дословная расшифровка обращения.

Vosk-модель остаётся загруженной в памяти для быстрого ответа; экономия CPU
достигается остановкой декодирования между командами, а не выгрузкой модели.
Аудиозаписи не сохраняются. На старте синтетический PCM-тест сверяет весь
ONNX pipeline с Python-эталоном; результат записывается в logcat `Pomogator`.
APK содержит `wakeword/config.json` с профилем, фразой, порогом, SHA-256 head
и synthetic self-test reference. Перед ONNX-сессиями Android проверяет hash
файла классификатора. Config/модели каждый запуск копируются из assets, поэтому
обновление не оставляет старый head в кеше приложения.

## Сборка и установка

Нужны OpenJDK 17 и Android SDK platform/build-tools 34.0.0. Из корня проекта:

```powershell
.\scripts\build-voice-color-station.ps1
.\scripts\install-voice-color-station.ps1
```

По умолчанию сборка использует публичную исходную модель (`generic`). Для
личной модели, которая намеренно не опубликована, нужен локальный run v2:

```powershell
.\scripts\build-voice-color-station.ps1 -WakeProfile personal-v2
.\scripts\install-voice-color-station.ps1
.\scripts\verify-wakeword-runtime.ps1 -ExpectedProfile personal-v2
```

Нужен PowerShell 7. SHA-256 обоих профилей и golden scores закреплены в
`training/pomogator/android-profiles.json`. Сборка создаёт обычный debug APK
и отдельную копию `voice-color-station-0.6.0-<profile>.apk` в ignored artifacts.

Первый сценарий скачивает закреплённые Vosk/JNA, русскую модель, R8/D8,
ONNX Runtime Android 1.22.0 и две feature-модели openWakeWord v0.5.1,
проверяет SHA-256, затем выполняет `aapt2 → javac → d8 → zipalign → apksigner`.
Кэш, APK, build и debug key не входят в Git. На устройстве хранятся только
распакованные модели и последний выбранный цвет.

Проверка после установки сверяет успешный ONNX self-test текущего PID, профиль,
model hash/threshold в logcat, готовность микрофона, ошибки и память.

Чтобы остановить микрофон, используйте действие «Остановить» в уведомлении.
Откат APK с компьютера:

```powershell
.\.tools\platform-tools\adb.exe shell am force-stop local.ivand.voicecolorstation
.\.tools\platform-tools\adb.exe uninstall local.ivand.voicecolorstation
```
