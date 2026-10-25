# Обработчик RTT

`timer_hook.S` взят из stm32-diag-record 1.8.0, ревизия 376225b,
автор Viacheslav Mezentsev, лицензия MIT (рядом).

Сборка эталона:

```sh
arm-none-eabi-as -mcpu=cortex-m4 -mthumb timer_hook.S -o stub.o
arm-none-eabi-ld -Ttext=0 -e timer_hook stub.o -o stub.elf
arm-none-eabi-objcopy -O binary --only-section=.text stub.elf stub.bin
```

Размер — 152 байта, параметры с позиции 120. SHA-256 незаполненного образа:
`856583cca9c26943882d13c01d94b0eb1961fc0c8fc1450e733ed1656750253c`.
RttImageBuilder содержит эти инструкции и заполняет параметры на телефоне;
RttImageBuilderTest сверяет хеш и размещение. Артефакты сборки не коммитятся.
