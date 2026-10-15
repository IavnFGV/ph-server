# Проверенный набор APK Termux

Дата проверки: 2026-10-15. APK пока не установлены.

## Termux

- Репозиторий: <https://github.com/termux/termux-app>
- Релиз: <https://github.com/termux/termux-app/releases/tag/v0.118.3>
- Файл: `termux-app_v0.118.3+github-debug_universal.apk`
- Размер: `117775723` байт
- SHA-256:
  `7600078440C3C34EF050BC009B00FC3215CB87EC4A449E01A696F74CF4249DB2`
- APK Signature Scheme: v2

## Termux:Boot

- Репозиторий: <https://github.com/termux/termux-boot>
- Релиз: <https://github.com/termux/termux-boot/releases/tag/v0.8.1>
- Файл: `termux-boot-app_v0.8.1+github.debug.apk`
- Размер: `743812` байт
- SHA-256:
  `97E6D336F05B59F7CB25E8EFA7173E0A6E665F3022B75C6D55A69B6399ECACC8`
- APK Signature Scheme: v1 и v2

## Подпись

Оба APK успешно проверены официальной библиотекой Google `apksig` 9.4.1 и
подписаны одним сертификатом:

- Subject: `CN=APK Signer, OU=Earth, O=Earth`
- SHA-256 fingerprint:
  `B6:DA:01:48:0E:EF:D5:FB:F2:CD:37:71:B8:D1:02:1E:C7:91:30:4B:DD:6C:4B:F4:1D:3F:AA:BA:D4:8E:E5:E1`

APK сверены с checksum-файлами, опубликованными в тех же официальных GitHub-
релизах. Инструмент `apksig-9.4.1.jar` получен из Google Maven по адресу
<https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/9.4.1/apksig-9.4.1.jar>;
его SHA-256:
`7AE2E5980C77D853E3513074EE7C822BBDCDCDE1668889D17FAA7FE8BC8AA821`.

## Проверка телефона перед установкой

Пакеты `com.termux` и `com.termux.*` на телефоне не обнаружены. Конфликта с
установкой из F-Droid или другого канала сейчас нет.
