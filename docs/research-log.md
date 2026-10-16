# Журнал исследований

Здесь будут сохраняться дата обращения, ссылка, тип источника, проверяемое
утверждение и итог. Приоритет источников: официальная документация, исходный
код и issue-трекеры проектов; технические обсуждения используются только как
дополнительные свидетельства.

## 2026-10-15

### Android SDK Platform Tools

- Официальная страница: <https://developer.android.com/tools/releases/platform-tools>
- Официальная инструкция по USB-отладке:
  <https://developer.android.com/studio/run/device>
- Проверено: Google рекомендует актуальную версию Platform Tools; постоянная
  ссылка для Windows указывает на последний выпуск. На Android 4.2.2 и новее
  ADB требует разблокировать экран и подтвердить RSA-ключ этого компьютера.
- Решение: загружать архив только с `dl.google.com`, вычислять SHA-256
  загруженного файла и хранить инструменты локально в `.tools`, не меняя
  системный `PATH`.

### OEM-драйвер LG для Windows

- Официальная инструкция Android:
  <https://developer.android.com/studio/run/oem-usb>
- Официальная страница LG:
  <https://www.lg.com/html/support/software-drivers.html>
- Android указывает, что в Windows для устройств OEM нужен соответствующий
  USB-драйвер, и направляет пользователей LGE на сайт LG.
- LG публикует `LG Universal Mobile Drivers` и заявляет совместимость пакета со
  всеми мобильными устройствами LG. Файл распространяется с домена LG
  `tool.cdn.gdms.lge.com`.
- Установка драйвера изменяет системное хранилище драйверов Windows, поэтому до
  явного согласия пользователя выполняются только проверки.
- На 2026-10-15 обе официальные ссылки на загрузку (`tool.cdn.gdms.lge.com` для
  Universal Mobile Driver и `lggdmstool.lge.com` для LG Bridge) возвращают
  `NXDOMAIN`; страницы поддержки LG остались доступны, но их загрузки устарели.
- Сторонние зеркала найдены, однако не используются без отдельного решения.
- PnP показывает, что LM-V510N сейчас публикует только MTP PID `633F`, без
  отдельного ADB-интерфейса. Поэтому перед поиском альтернативного источника
  драйвера нужно добиться экспонирования ADB со стороны телефона.

После отдельного разрешения пользователя пакет был получен с зеркала Octoplus:
<https://octoplusbox.com/files/drivers/LGMobileDriver_WHQL_Ver_4.8.0.exe>.
Перед запуском проверены Authenticode (`Valid`), подписант `LG Electronics Inc.`,
версия 4.8.0 и SHA-256
`2B0B3DADBD35FE4CBD15B262196B082E561C8587D40D97E0FAE3789D1777AFE8`.
Установка завершилась с кодом 0, после чего появился интерфейс
`LGE Mobile ADB Interface` и ADB-соединение заработало.

### Актуальные способы установки Home Assistant

- Объявление об изменении поддержки:
  <https://www.home-assistant.io/blog/2025/05/22/deprecating-core-and-supervised-installation-methods-and-32-bit-systems/>
- Официальная установка Home Assistant Container на Linux:
  <https://www.home-assistant.io/installation/linux/>
- PyPI Home Assistant: <https://pypi.org/project/homeassistant/>
- Приложения Home Assistant: <https://www.home-assistant.io/apps>
- Проверено: начиная с выпуска 2025.12 официально поддерживаются только Home
  Assistant OS и Home Assistant Container. Core и Supervised продолжают
  технически существовать, но не являются поддерживаемыми способами установки.
- Актуальный Home Assistant 2026.10.1 требует Python `>=3.14.2`.
- Apps/add-ons управляются Supervisor и доступны как поддерживаемая функция
  Home Assistant OS; в Container/Core сопутствующие службы устанавливаются и
  обслуживаются отдельно.
- Вывод: на Android без совместимого OCI runtime возможен только
  экспериментальный, неподдерживаемый Home Assistant Core либо эмуляция
  контейнерной файловой системы через PRoot. Это важно отличать от официально
  поддерживаемого Home Assistant Container.

### Termux и PRoot

- Termux: <https://github.com/termux/termux-app>
- Termux:Boot: <https://github.com/termux/termux-boot/blob/master/README.md>
- PRoot Distro: <https://github.com/termux/proot-distro>
- Пакеты Termux: <https://github.com/termux/termux-packages>
- Проверено: стабильные сборки Termux следует получать через F-Droid или
  GitHub проекта; ветка Google Play обозначена проектом как экспериментальная.
- В Android 12 Termux предупреждает о механизме phantom process killer:
  фоновые процессы и процессы с высокой нагрузкой могут быть остановлены ОС.
- Termux:Boot запускает сценарии из `~/.termux/boot` после загрузки; проект
  рекомендует `termux-wake-lock` для сервисов, которым требуется не засыпать.
- PRoot не даёт настоящего root, namespaces, cgroups, seccomp или systemd и
  добавляет накладные расходы `ptrace`. Поэтому Docker/Podman внутри PRoot не
  являются рабочей архитектурой, хотя отдельные долгоживущие процессы Linux
  запускать можно.
- В официальном репозитории Termux доступны Python 3.14 и Mosquitto. Это
  позволяет построить proof of concept без root, но не устраняет ограничения
  Android по жизненному циклу фоновых процессов.
- В issue-трекере Termux есть актуальные сообщения о повышенной фоновой
  нагрузке Home Assistant в PRoot/udocker. Они рассматриваются как сигнал риска,
  а не как доказательство стабильности конкретного телефона.

### Требования Docker и сопоставление с телефоном

- Требования Moby к ядру:
  <https://github.com/moby/moby/blob/master/project/PACKAGERS.md>
- Скрипт проверки конфигурации ядра Moby:
  <https://github.com/moby/moby/blob/master/contrib/check-config.sh>
- Требования драйвера `overlay2`:
  <https://docs.docker.com/engine/storage/drivers/overlayfs-driver/>
- Moby требует набор namespaces, cgroup-контроллеров, сетевых функций ядра и
  подходящий storage driver. Для cgroup v2 нужны как минимум доступные
  контроллеры CPU, cpuset, I/O, memory и pids.
- На LM-V510N отсутствуют наблюдаемые PID/IPC/user namespaces, необходимые
  cgroup-контроллеры, а cgroup v2 смонтирован без контроллеров. OverlayFS не
  объявлен в `/proc/filesystems`.
- Вывод: отсутствие Docker вызвано не только отсутствием root. Даже после
  установки Magisk штатное ядро останется несовместимым; потребуется отдельное
  кастомное ядро и его долговременное сопровождение.

### Нативный Linux для LM-V510N / mh2lm

- Страница точного варианта устройства в LineageOS Wiki:
  <https://lineage-wiki.mainlining.org/devices/mh2lm/variant3/>
- Список актуальных образов postmarketOS:
  <https://postmarketos.org/download/>
- Найденные проекты UEFI/Windows on ARM подтверждают существование низкоуровневых
  экспериментов с `mh2lm`, но требуют изменения разметки/загрузки и сами
  предупреждают о риске повреждения устройства.
- Для семейства `lg-mh2lm` обнаружена экспериментальная работа в экосистеме
  postmarketOS/mainline: таблица устройств утверждает, что порт как минимум
  загружался, а страница Snapdragon 855 отмечает работающие Wi-Fi, Bluetooth и
  thermal для LG G8X ThinQ. LM-V510N также сообщает codename `mh2lm`, но это
  само по себе не доказывает совместимость конкретного корейского варианта.
- На 2026-10-15 в текущем HEAD официального `pmaports`
  (`0e458a2417d6f5ed61c7b70621d5632e30136091`) отсутствуют пути с `mh2lm`,
  `g8x` или `v50s`; готового пакета категории main/community/testing не найдено.
  Прямые проверки ожидаемых путей `device-lg-mh2lm` в GitLab дали HTTP 404.
- Поэтому формулировка «порта нет вообще» уточнена: существуют следы
  экспериментального mainline-порта, но нет найденного актуального,
  поддерживаемого и устанавливаемого релиза. Не подтверждены критичные для
  headless-сервера UFS, зарядка, suspend/resume, watchdog, USB и надёжный reboot.
- Вывод: нативный Linux является возможным отдельным R&D-проектом, а не
  готовым способом снизить сопровождение домашнего сервера.

### Покупка поддерживаемого Linux-телефона вместо mini PC

- Текущее дерево `pmaports` подтверждает категорию `community` для OnePlus 6
  (`enchilada`), OnePlus 6T (`fajita`), Xiaomi Poco F1 (`beryllium`), Google
  Pixel 3a (`sargo`), Fairphone 4 и SHIFT6mq. Pixel 3 XL и часть старых
  downstream-портов находятся в `archived`; наличие старой wiki-страницы не
  следует считать текущей поддержкой.
- OnePlus 6/6T и Poco F1 на Snapdragon 845 — наиболее интересные дешёвые
  телефонные кандидаты: mainline Linux, 6–8 GB RAM, UFS и встроенные экран с
  аккумулятором. Однако это community-порты, не серверные платформы. Для
  OnePlus 6 документация отдельно отмечает необходимость powered USB hub для
  части сценариев USB host.
- У Snapdragon сильное мобильное железо, но доступность Adreno/Hexagon/NPU в
  mainline Linux не означает, что Whisper/Piper или обычные Docker-образы умеют
  его использовать. Большинство серверного ПО получит ARM64 CPU и NEON, но не
  рекламную производительность Android-драйверов DSP/NPU.
- Home Assistant официально рекомендует Intel N100 или эквивалент как минимум
  для полностью локального Whisper Base. N100 имеет 4 ядра/4 потока, AVX2,
  Quick Sync и официальный TDP 6 W; ценность платформы — не только CPU, но и
  16 GB RAM, NVMe, Ethernet и зрелый x86-64 Linux/Docker.
- Срез украинского рынка 2026-10-15: N100 16/512 встречается примерно за
  5,200–8,888 грн б/у и около 9,429 грн новым; это цены отдельных объявлений,
  не гарантия качества или наличия. Бизнес-ноутбуки на i5-8250U/i5-8350U с
  экраном, батареей, SSD и 8–16 GB RAM встречаются примерно за 6,000–10,000 грн.
- Для требования «экран и мини-UPS из коробки» б/у бизнес-ноутбук является
  более предсказуемой альтернативой Linux-телефону: стандартный Linux,
  заменяемый NVMe/SATA, Ethernet/USB и отсутствие device-porting слоя. Цена —
  более высокое энергопотребление и больший корпус.
- Источники: Intel N100 specifications
  <https://www.intel.com/content/www/us/en/products/sku/231803/intel-processor-n100-6m-cache-up-to-3-40-ghz/specifications.html>,
  Home Assistant Voice PE <https://www.home-assistant.io/voice-pe/>, OnePlus 6
  postmarketOS <https://wiki.postmarketos.org/wiki/OnePlus_6_(oneplus-enchilada)>,
  текущий `pmaports`, а также срезы актуальных объявлений OLX/украинских
  магазинов. Рыночные цены должны перепроверяться непосредственно перед
  покупкой.

### Meizu Pro 7 Plus как бесплатный Linux-хост

- Meizu Pro 7 Plus (`m1793`/`PRO7Plus`) использует MediaTek Helio X30
  `MT6799`, 6 GB RAM, UFS 2.1 и PowerVR 7XTP-MT4; это не Snapdragon.
- В текущем HEAD `pmaports` нет путей `mt6799`, `m1793` или `pro7`. Все
  найденные Meizu packages относятся к M2 (`mblu2`) и Pro 5 (`turbo`) и уже
  находятся в `device/archived`; Pro 5 нельзя путать с Pro 7 Plus.
- В mainline Linux присутствует базовое описание близкого MT6797 и evaluation
  board, но `arch/arm64/boot/dts/mediatek/mt6799.dtsi` отсутствует. Vendor DTS
  Meizu для MT6799 не является upstream-поддержкой: его драйверы рассчитаны на
  старое Android/vendor-ядро.
- Отсутствуют готовый board device tree и подтверждённый стек для UFS, PMIC,
  зарядки, Wi-Fi, обоих AMOLED, touch и PowerVR. Второй экран добавляет отдельный
  display path и увеличивает объём porting, а не упрощает его.
- Вывод: как бесплатный Android/Termux POC Meizu проверить можно после
  диагностики; как нативный Linux/Docker-хост он существенно хуже OnePlus 6/6T
  или Poco F1 и потребует самостоятельного bring-up практически с нуля.

### Доступ к ускорителям Snapdragon 855

- На stock Android наиболее полный поддерживаемый путь — Android NNAPI либо
  Qualcomm QAIRT/SNPE/QNN из нативного C++/Java-приложения. Qualcomm описывает
  backends CPU, Adreno GPU и Hexagon DSP/HTP; модель требуется конвертировать и
  проверить по поддерживаемым операциям/точности.
- Фактическая read-only диагностика LM-V510N подтвердила Android HAL NNAPI
  версий 1.0–1.3 с устройствами `qti-default`, `qti-dsp`, `qti-gpu`, `qti-hta`.
  Android объявляет `android.hardware.vulkan.compute`, Vulkan level 1 и GLES
  3.2; в vendor находятся `libOpenCL.so`, FastCV и HTA runtime, а `/dev`
  содержит `adsprpc-smd` и secure-вариант. Значит, ускорители доступны stock
  Android vendor stack, хотя `dumpsys neuralnetworks` на этой прошивке не
  зарегистрирован как dump-service.
- Ubuntu PRoot не может штатно загружать эти `.so`: Ubuntu использует glibc, а
  Android vendor libraries собраны для Bionic и защищены SELinux/vendor ABI.
  Root не устраняет ABI и совместимость runtime. Практичный мост — нативный
  Android foreground service/APK, использующий NNAPI/SDK и отдающий локальный
  HTTP/gRPC/Unix-socket API Home Assistant.
- Более простой первый тест — TensorFlow Lite с NNAPI delegate: он не требует
  прямого открытия DSP device nodes и позволяет Android HAL выбрать qti backend.
  Но неподдерживаемые операции уйдут на CPU; ускорение Whisper нельзя обещать
  без конвертации и измерений.
- Текущая публичная таблица QAIRT не перечисляет мобильный Snapdragon 855
  `SM8150`, хотя vendor runtime на устройстве присутствует. Поэтому до разработки
  нужно проверить совместимость конкретной версии SNPE/QNN либо использовать
  системный NNAPI как стабильную абстракцию.
- В mainline Linux Adreno 6xx может работать через Mesa Freedreno/Turnip
  (OpenGL/Vulkan), но это не открывает автоматически Hexagon/HTA. Полный DSP-
  путь требует firmware, remoteproc/FastRPC, подходящих stub/skel libraries,
  device tree, memory regions и совместимого Qualcomm runtime.
- Источники: Qualcomm QAIRT
  <https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-10/general_overview.html>,
  Qualcomm supported devices
  <https://docs.qualcomm.com/nav/home/QNN_general_overview.html?product=924033590759186372>,
  Mesa Freedreno <https://docs.mesa3d.org/drivers/freedreno.html>.

### Интеграции WiZ, MQTT, Matter и ESPHome

- WiZ: <https://www.home-assistant.io/integrations/wiz/>
- MQTT: <https://www.home-assistant.io/integrations/mqtt/>
- Matter: <https://www.home-assistant.io/integrations/matter/>
- ESPHome: <https://esphome.io/install/>
- WiZ работает локально и поддерживает local push. Для обнаружения используется
  UDP broadcast, поэтому телефон и устройства должны находиться в одной LAN;
  при проблемах обнаружения можно проверить добавление по IP.
- В Core/Container MQTT-брокер устанавливается отдельно. Mosquitto доступен как
  пакет Termux, поэтому его можно тестировать как отдельный процесс.
- Официально поддерживаемый путь Matter Server — приложение Home Assistant OS.
  Отдельный контейнер Matter Server возможен только как самостоятельно
  обслуживаемая конфигурация и не подходит для первого этапа на этом телефоне.
- ESPHome можно запускать отдельно на компьютере или сервере; первичную USB-
  прошивку микроконтроллеров разумнее делать с ноутбука, а затем использовать
  OTA. Одновременная зарядка телефона и USB OTG на данном экземпляре не
  проверена.

### Google Wallet после прежнего Magisk и сервисного восстановления

- Требования Google Wallet:
  <https://support.google.com/wallet/answer/12200245>
- Проверка сертификации Play Protect:
  <https://support.google.com/android/answer/7165974>
- Описание verdict Play Integrity:
  <https://developer.android.com/google/play/integrity/setup>
- Google прямо указывает разблокированный загрузчик, root, custom ROM и
  модифицированное заводское ПО среди причин отказа бесконтактной оплаты.
- В текущей диагностике телефона: `ro.boot.flash.locked=0`,
  `ro.boot.vbmeta.device_state=unlocked`, `ro.boot.verifiedbootstate=orange`.
  Root и установленный Magisk сейчас не обнаружены.
- Наиболее вероятное объяснение: сервис восстановил загрузку Android, но не
  вернул загрузчик и цепочку Verified Boot в заводское locked/green-состояние.
  Этого достаточно для отказа Wallet даже без Magisk.
- Удаление только пользовательского APK/значка Magisk обычно не объясняет
  bootloop. Вероятнее, в прошлом оставался изменённый или несовместимый
  `boot`/`vbmeta`, либо сбой проявился при перезагрузке. Без старых образов и
  журналов это остаётся обоснованной гипотезой, а не доказанным фактом.
- Безопасная пользовательская проверка: Play Store → профиль → Настройки →
  О приложении → «Сертификация Play Protect». Никаких изменений системы для
  этой проверки не требуется.

### Фоновый микрофон и системное распознавание Android

- Официальная документация `SpeechRecognizer`:
  <https://developer.android.com/reference/android/speech/SpeechRecognizer>
  предупреждает, что реализация может передавать аудио удалённым серверам и API
  не предназначен для непрерывного распознавания из-за расхода батареи и сети.
- Официальное описание microphone foreground-service:
  <https://developer.android.com/develop/background-work/services/fgs/service-types>
  требует `RECORD_AUDIO`, service type `microphone` и видимое уведомление.
- Ограничения Android 12+:
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
  запрещают обычный старт foreground-service из фона. Микрофонная служба
  поэтому запускается из видимой Activity после уже выданного разрешения.
- Для POC принят foreground-service с циклическими recognition-сессиями.
  Финальная always-on архитектура должна использовать локальный wake-word,
  чтобы не отправлять постоянный поток речи и уменьшить энергопотребление.

### Локальные альтернативы после системных звуков SpeechRecognizer

- Пользовательская проверка показала системный звук при каждом цикле даже на
  подтверждённо беззвучном notification channel. Циклический SpeechRecognizer
  признан непригодным для домашней станции и отключён.
- Vosk Android: <https://alphacephei.com/vosk/android> — официальный пример
  offline streaming ASR для Android с AAR; поддерживается русский язык.
- Каталог Vosk: <https://alphacephei.com/vosk/models> — русская small-модель
  `vosk-model-small-ru-0.22` имеет размер 45 МБ; small-модели рассчитаны на
  смартфоны/Raspberry Pi и обычно требуют около 300 МБ RAM.
- Sherpa-ONNX KWS:
  <https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/kws/index.rst>
  предоставляет Android-приложение и open-vocabulary custom keywords без
  переобучения. До выбора требуется проверить русскую модель и размер runtime.
- OpenWakeWord ориентирован прежде всего на Python/Linux и кастомную обученную
  модель; готовая Android-интеграция для слова «Помогатор» не подтверждена.

### Зафиксированные зависимости Vosk POC

- Официальный Android demo использует `com.alphacephei:vosk-android:0.3.75@aar`
  и `net.java.dev.jna:jna:5.18.1@aar`:
  <https://github.com/alphacep/vosk-android-demo/blob/master/app/build.gradle>.
- Русская small-модель: `vosk-model-small-ru-0.22`, 45 МБ, Apache 2.0:
  <https://alphacephei.com/vosk/models>.
- Для совместимости JNA 5.18.1 с ручной сборкой закреплён стабильный R8/D8
  `9.5.26` из Google Maven. Все четыре файла проверяются по SHA-256 в
  `scripts/prepare-vosk-dependencies.ps1`.
- Поиск в `words.txt` модели не нашёл «помогатор», но подтвердил «помощник» и
  «помоги». Ограниченная грамматика Vosk не может надёжно распознавать слово,
  отсутствующее в лексиконе; для него нужен отдельный KWS/phonetic model.

### Проверка custom KWS для «Помогатора»

- Sherpa-ONNX действительно предоставляет open-vocabulary keyword spotting и
  Android API, где ключевые слова задаются без переобучения:
  <https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/kws/index.rst>.
- Но официальный каталог готовых KWS-моделей сейчас содержит Chinese,
  Chinese+English и English; Russian-модели в списке нет:
  <https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/kws/pretrained_models/index.rst>.
- На данном LG полный русский Vosk транскрибировал произнесённое «Помогатор»
  как `помогать`. Это значение принято как узкий device-tested alias, пока не
  появится или не будет обучена настоящая русская KWS-модель.

### Как получить настоящий акустический trigger «Помогатор»

- OpenWakeWord позволяет обучать собственную компактную ONNX/TFLite-модель на
  синтетических и реальных записях, но upstream официально считает готовую
  поддержку English-only; русский потребует собственного набора речи и
  обязательной проверки false accepts/rejects:
  <https://github.com/dscripka/openWakeWord>.
- Существует сторонний Android runtime, принимающий custom ONNX-модели
  openWakeWord и работающий с `AudioRecord`:
  <https://github.com/msnilsen/openwakeword-android>. Это удобная точка
  интеграции, но не официальный компонент upstream, поэтому код и лицензии
  нужно отдельно проверить перед включением.
- Практический путь: записать 20–50 реальных произнесений «Помогатор» разными
  голосами/дистанциями, сгенерировать тысячи русских TTS-вариантов, добавить
  отрицательные примеры и похожие слова, обучить ONNX detector, проверить его
  на часах бытового аудио, затем использовать detector только для пробуждения,
  а Vosk — для команды после trigger.
- microWakeWord также обучает custom TFLite-модели, но его готовый runtime
  ориентирован на ESPHome/TFLite Micro. Для текущего Android-приложения путь
  через openWakeWord ONNX короче.

### Android 17 и варианты прошивки mh2lm

- Android 17 официально выпущен 16 июня 2026:
  <https://developer.android.com/blog/topics/android-17>.
- Официальные GSI Android 17 предназначены для разработки/тестирования, не
  CTS-approved для произвольного LG и могут стереть данные или оставить часть
  оборудования неработающей:
  <https://developer.android.com/about/versions/17/gsi-release-notes>.
- В LineageOS присутствует дерево `android_device_lge_mh2lm` с веткой 22.2:
  <https://github.com/LineageOS/android_device_lge_mh2lm>. Карточка варианта
  перечисляет LM-G850QM/UM, LineageOS 20/21 и Android 12 firmware prerequisite,
  но не корейский LM-V510N:
  <https://github.com/LineageOS/lineage_wiki/blob/main/_data/devices/mh2lm_variant2.yml>.
- Найденная community LineageOS 21/Android 14 для G8X/V50S закрыта автором:
  <https://xdaforums.com/t/closed-rom-unofficial-14-lineageos-21-0-for-lg-g8x-v50s-mh2lm.4673839/>.
  Unofficial LineageOS 22.2/Android 15 существует, но автор отмечает
  неработавший fingerprint:
  <https://xdaforums.com/t/rom-unofficial-15-lineage-os-22-2-for-lg-g8x-mh2lm.4748623/>.
- Вывод для текущего серверного POC: оставаться на stock Android 12 до полного
  backup/restore-плана. Более новая ОС не требуется для локального wake-word и
  не компенсирует риск утраты vendor-функций или рабочего серверного стека.

### Проверка официального Colab и русский training pipeline

- В актуальном `automatic_model_training_simple.ipynb` (заметка датирована
  2026-04-11) пример клонирует `rhasspy/piper-sample-generator`, переключается
  на `213d4d5` и загружает английский `en_US-libritts_r-medium.pt`. Форма
  советует удалять Unicode. Это подтверждает, что прямой ввод «Помогатор» не
  является русским обучением.
- Официальный pipeline использует MIT RIR, одну часть AudioSet, час FMA,
  ACAV100M features и 11 часов validation features. Проверенные размеры двух
  `.npy`: 17 280 000 128 и 184 836 608 байт; Colab должен иметь достаточный
  временный диск.
- Для русской генерации выбраны Piper voices `ru_RU-denis-medium`,
  `ru_RU-dmitri-medium`, `ru_RU-irina-medium`, `ru_RU-ruslan-medium` из тега
  `v1.0.0`. Все четыре ONNX URL ответили HTTP 200, размер каждого 63 201 294
  байта.
- `training/pomogator/generate_russian_samples.py` создаёт штатную структуру
  openWakeWord `positive_train`, `positive_test`, `negative_train`,
  `negative_test`, а затем ресемплирует результат Piper в mono 16-bit PCM
  16 кГц. Без этого openWakeWord `augment_clips` отклонит 22,05-кГц Piper WAV.
- Notebook закрепляет upstream commit SHA и сохраняет SHA-256 итоговой ONNX в
  `training-report.txt`. Из-за смешанных лицензий официальных датасетов модель
  маркируется как личный некоммерческий POC.

### 2026-10-16 — результаты самостоятельного обучения в Colab

Пользовательский экспорт выявил недостающие условия запуска: legacy Piper
нужен в PYTHONPATH, datasets 2.14.6 требует совместимого PyArrow (20.0.0),
RIR должны быть реальными WAV после Git LFS, а рабочий AudioSet URL находится
в корне закреплённой ревизии `196c0900867eff791b8f4d4be57db277e9a5b131`.
SHA-256 архива из успешного пользовательского запуска:
`9ab88550e77c8289acf71d19ae3f254fadad16f3cea305ef08a16949a928acf9`.

Для нового TorchAudio метаданные читаются через soundfile. ONNX export
использует `dynamo=False` и opset 13; обученные веса сохраняются до экспорта,
чтобы его ошибка не требовала нового обучения. Строковый default `"False"`
у CLI-флага TFLite truthy, поэтому проверка заменена на `is True`.

Пользовательский ONNX независимо проверен и запускается на CPU. Он принимает
16×96 признаков openWakeWord, а не сырой PCM: Android runtime также потребует
штатные melspectrogram и embedding модели. Точность wake-word пока не измерена;
положительный результат checker и inference не доказывает качество обучения.

### 2026-10-16 — потоковый ONNX runtime на LG

Использованы официальный Java API ONNX Runtime
<https://onnxruntime.ai/docs/get-started/with-java.html> и потоковый алгоритм
`AudioFeatures` из закреплённого openWakeWord. Сторонний Kotlin-порт изучен
как пример, но в APK добавлен собственный Java-модуль без второго AudioRecord.
ORT Android 1.22.0 выбран с полным набором ONNX ops, двумя ARM64 `.so` и
проверенной подписью SHA-256 артефакта. Полный runtime нужен mel/embedding
моделям; уменьшенный runtime отдельно не проверялся.

На синтетическом PCM полный Android pipeline согласован с CPU Python по
выходу classifier и одному embedding feature. Это проверяет перенос
форматов/масштаба звука и загрузку трёх моделей; для оценки качества нужны
настоящие произнесения и фон. Vosk-модель загружена постоянно, а декодирование
включается только в окне команды. Экономия CPU/батареи относительно 0.4.3
пока не измерена.


