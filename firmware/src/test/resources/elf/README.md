# Тестовые ELF

`t4.elf` и `t5.elf` собраны из `t.c` и `l.ld` (DWARF 4 и 5, Cortex-M4):

```sh
arm-none-eabi-gcc -mcpu=cortex-m4 -mthumb -mfloat-abi=hard -mfpu=fpv4-sp-d16 -O1 -g -gdwarf-5 \
  -fdebug-prefix-map=$PWD=. -nostdlib -T l.ld t.c -o t5.elf -lgcc
```

Сборка: GNU Arm Embedded GCC 13.2.1 (пакет `gcc-arm-none-eabi` Ubuntu 24.04).
