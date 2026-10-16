# Откат

## Текущее состояние после установки APK

На телефоне установлены только:

- `com.termux`, версия 0.118.3;
- `com.termux.boot`, версия 0.8.1.

Приложения ещё не запускались, bootstrap и серверное окружение не создавались,
настройки Android не менялись. Поэтому полный откат текущего этапа — удалить
сначала Termux:Boot, затем Termux.

## Воспроизводимый откат

Из корня проекта:

```powershell
.\scripts\uninstall-termux.ps1 -ConfirmRemoval
```

Скрипт эквивалентен следующим ADB-действиям:

```powershell
adb shell am force-stop com.termux.boot
adb uninstall com.termux.boot
adb shell am force-stop com.termux
adb uninstall com.termux
```

Важно: после начала настройки Termux удаление `com.termux` уничтожит его
приватный домашний каталог, пакеты, конфигурацию Home Assistant и базу данных.
Перед поздним откатом нужно сначала выполнить отдельный экспорт данных.

Скрипт намеренно требует `-ConfirmRemoval` и сейчас не запускался.

## Вернуть прежний обработчик Home

В ходе настройки обнаружено, что прежним Home был назначен сетевой экран
«Предложения» `com.lge.appbox.client/.DpPreloadIntro`. Он заменён на штатный
LG Launcher без очистки данных:

```powershell
adb shell cmd package set-home-activity com.lge.launcher3/.LauncherExtension
```

Если по какой-либо причине потребуется вернуть прежнее состояние:

```powershell
adb shell cmd package set-home-activity com.lge.appbox.client/.DpPreloadIntro
```

Откат Home не удаляет Termux и его данные.

## Вернуть оптимизацию батареи

Termux и Termux:Boot добавлены в device-idle whitelist. Удалить исключения можно
без удаления приложений и данных:

```powershell
adb shell dumpsys deviceidle whitelist -com.termux
adb shell dumpsys deviceidle whitelist -com.termux.boot
```

Глобальные настройки Android и phantom-process monitor не изменялись.

## Удалить Ubuntu PRoot

После установки контейнера `ha-ubuntu` его можно удалить независимо от Termux:

```text
proot-distro remove ha-ubuntu
```

Команда уничтожает весь rootfs и будущие данные Home Assistant внутри него,
но не удаляет Termux. Перед использованием на настроенном сервере требуется
сначала создать backup. На этапе пустого rootfs откат безопасен.

Если нужно удалить только Home Assistant, сохранив Ubuntu, следует остановить
его процессы и удалить `/srv/homeassistant`. Эта операция также уничтожит
конфигурацию и базу данных, поэтому отдельный rollback-скрипт будет добавлен
только после реализации backup.

Home Assistant/PRoot-сессию можно безопасно остановить без удаления данных:

```text
~/05-stop-home-assistant.sh
```

Скрипт вызывает `proot-distro kill ha-ubuntu` с обычным `SIGTERM` и эскалацией,
если гостевой процесс не завершится.

## Удалить прототип Voice Color Station

### Вернуть исходный детектор без удаления данных

Для отката с personal-v2 на generic не нужно удалять приложение:

```powershell
.\scripts\build-voice-color-station.ps1 -WakeProfile generic
.\scripts\install-voice-color-station.ps1
.\scripts\verify-wakeword-runtime.ps1 -ExpectedProfile generic
```

Оба текущих профиля имеют версию 0.7.1 / versionCode 14 и подписаны тем же локальным
ключом: `adb install -r` заменяет APK с сохранением данных. Это возврат
исходной модели/порога, не downgrade всего приложения на 0.5.0/0.6.0.
Прежний APK 0.5.0 сохранён локально в ignored artifacts как
`voice-color-station-0.5.0-generic-backup.apk`; его прямая установка поверх
новой версии может быть запрещена Android как downgrade. Не обходить это удалением
пакета, если нужно сохранить данные. Персональная копия APK также остаётся
локально; она содержит личные веса и не должна публиковаться вместе с логами.

Вернуть именно 400-мс pre-roll, сохранив данные: собрать прежний исходник
VoiceListenerService из commit `24d36e3` отдельно с versionCode не ниже
установленного и прежним ключом, затем `install -r`. Простая установка
сохранённого APK 0.6.0/code11 поверх 0.6.1/code12 может быть запрещена;
автоматический downgrade/удаление приложения не выполняются.

### Полностью удалить прототип

Если для «отбой» был активирован ScreenLockAdminReceiver, сначала отключить
«Голосовой цвет» в системном списке администраторов устройства (обычно
Настройки → Безопасность → Администраторы устройства). Активный Device Admin
может блокировать обычный uninstall. Отключение администратора отменяет только
возможность lockNow; для выключения микрофона используйте «Остановить».
Снятие admin через device-owner/debug обходы не используется.

Прототип устанавливается отдельным Android-пакетом и не изменяет Termux,
Ubuntu или Home Assistant. Остановить и полностью удалить только его можно так:

```powershell
adb shell am force-stop local.ivand.voicecolorstation
adb uninstall local.ivand.voicecolorstation
```

Остановить только фоновое прослушивание без удаления приложения можно действием
«Остановить» в его постоянном Android-уведомлении либо через ADB:

```powershell
adb shell am force-stop local.ivand.voicecolorstation
```

Следующее открытие приложения снова запустит службу, пока Activity видима.
`stopservice` из ADB для намеренно неэкспортированной службы на LG возвращает
ошибку; `force-stop` ограничен только этим пакетом и не удаляет его данные.

В 0.7.0 остановка службы также освобождает CPU wake lock. Действие «Остановить»
отключает boot-напоминание до следующего ручного запуска приложения. Overlay
и исключение батареи можно отозвать в системных настройках только этого APK;
отзыв overlay сам по себе не выключает микрофон — сначала остановите службу.
Полный возврат поведения 0.6.1 — прежний исходник из `93ff5b3`, сборка с
versionCode не ниже установленного и прежним ключом, затем install -r.

Удаление стирает только локальные данные прототипа и выданное ему разрешение
на микрофон. Собранный APK и исходники в этом репозитории сохраняются.
