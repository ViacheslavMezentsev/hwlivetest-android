# GitHub Pages

Исходники сайта — `docs/site/`. Локальная проверка: `python tools/check_site.py`.
Для просмотра: `python -m http.server 8765 --directory docs/site`, затем откройте
`http://localhost:8765`.

В репозитории откройте Settings → Pages → Build and deployment → Source и выберите
**GitHub Actions**. Workflow `Pages` проверяет сайт в pull request и публикует его
только из `main`; также доступен ручной запуск. Страница появится по адресу
<https://viacheslavmezentsev.github.io/hwlivetest-android/> после успешной публикации.

Цвета и круглый переключатель тем повторяют сайт
[stm32-gdbtest](https://viacheslavmezentsev.github.io/stm32-gdbtest/).
Режимы: тёмная → светлая → системная. Тема по умолчанию тёмная; выбор хранится
в общем для STM32-сайтов ключе `demo-stm32-skills-theme` на домене GitHub Pages.
Код темы и базовые стили взяты из проекта того же автора под лицензией MIT.

Ссылки «Релизы и APK» ведут на опубликованные GitHub Releases. Описания версий
ведутся в [docs/releases](releases/README.md). Страница не обещает доступность
конкретной версии до её публикации. APK и другие сборочные артефакты не входят в сайт.

Блок «Локальные эксперименты» отделён от доступных функций релиза и синхронизируется
с [состоянием экспериментов](polling-experiments.md). Обновление исходников сайта
в локальной ветке не публикует Pages; публикация произойдёт после согласованного
переноса изменений в main.
