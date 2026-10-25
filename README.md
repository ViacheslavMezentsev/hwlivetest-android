# HW Live Test (Android)

**Диагностика Cortex-M, работа с прошивкой и графики сигналов с телефона через ST-Link.**
Подключение по USB OTG, без Termux, OpenOCD и GDB. Протокол реализован на Kotlin.

[Сайт](https://viacheslavmezentsev.github.io/hwlivetest-android/) ·
[Релизы и APK](https://github.com/ViacheslavMezentsev/hwlivetest-android/releases)

## Быстрый старт

Нужны Android 8.0+ с USB Host (OTG), ST-Link V2/V2-1/V3 и плата с доступным SWD.

1. Установите APK из релиза.
2. Подключите ST-Link к плате и телефону, разрешите доступ к USB.
3. Нажмите **«Проверить плату»** или откройте экспертный режим.
4. Для графиков загрузите `addresses.json` либо таблицу из совпавшего ELF.

## Возможности

- Опознание Cortex-M, диагностика ядра, исключений и сбоев; профили MCU.
- Резервная копия Flash, сверка HEX/ELF, прошивка и стирание поддерживаемых STM32F1/F4.
- Живые сигналы, выбор каналов, масштабирование графиков и запись MCAP (ROS 1).
- Запись RTT для поддержанных STM32F1/F4: 100/500/1000 Гц, запуск и остановка с графика.
- Общее подключение между экранами, журнал действий и трафик ST-Link.

По умолчанию — чтение. Управление ядром и изменение Flash требуют включения доступа.
Option bytes и RDP не изменяются. Состав диагностики и поддержка прошивки зависят от MCU.

## Документация

- [Руководство: экраны, настройки, установка и сборка](docs/usage.md).
- [Практический пример addresses.json](docs/usage.md#пример-addressesjson) ·
  [спецификация формата](docs/addresses-json.md).
- [Эвристики диагностики](docs/heuristics.md) · [архитектура](docs/architecture.md).
- [Состояние локальных экспериментов: SWD, RTT, J-Link](docs/polling-experiments.md).
- [Описания релизов](docs/releases/README.md) · [планы — TODO](TODO.md).

## Сборка

JDK 17+ и Android SDK:

```shell
./gradlew test assembleDebug
```

В PowerShell: `./gradlew.bat test assembleDebug`. Подробности — в руководстве.

## Лицензия

[MIT](LICENSE).
