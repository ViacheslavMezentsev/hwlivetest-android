# Графика

| Файл | Что это |
| :--- | :--- |
| `icon-1024.png` | исходник иконки приложения: плата и MCU — HW, импульс — Live, круг со стрелками — HIL |
| `social-preview.png` | карточка репозитория 1280×640 для GitHub (Settings → Social preview) и README |
| `make_badge.py` | скрипт карточки: `python3 art/make_badge.py <папка со шрифтами>` |

Карточка рисуется шрифтом JetBrains Mono (OFL 1.1): нужны `JBM-Regular.ttf`, `JBM-Medium.ttf`,
`JBM-Bold.ttf`. Шрифты в репозиторий не входят; их можно получить из пакета npm `jetbrains-mono`
(woff2 → ttf через fontTools).
