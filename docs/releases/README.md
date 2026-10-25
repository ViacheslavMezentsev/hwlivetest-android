# Описания релизов

Полная история изменений ведётся в [CHANGELOG](../../CHANGELOG.md): раздел `Unreleased`,
затем версии от новых к старым с датой, изменениями, проверками и ограничениями.
Промежуточные локальные сборки объединяются в запись ближайшего выпуска.

Файл `<версия>.md` содержит краткое описание для GitHub Release: основные изменения,
ссылку на соответствующий раздел CHANGELOG, имя APK и контрольную сумму.
Ссылки в описании абсолютные, чтобы работать и на странице GitHub Release.
Прежние описания 0.3.13 и 0.3.16 сохранены в исходном виде.

## Порядок выпуска

1. Синхронизировать CHANGELOG, README, руководство, TODO и Pages с реализацией.
2. Выполнить `test assembleDebug`, проверить подпись и SHA-256 APK, записать результат
   в CHANGELOG и подготовить краткое описание в этой папке.
3. Переподписать и отправить рабочую ветку, дождаться CI последнего коммита,
   выполнить обычный цикл `git land`.
4. Создать и опубликовать тег вручную. Агент теги не создаёт и не перемещает.
5. Для существующего тега создать GitHub Release с описанием и проверенным APK.
   Публикация тега сама по себе пока не создаёт релиз.

Версия тега и исходный код APK должны соответствовать друг другу. Переподпись
коммитов меняет SHA: соответствие проверяется по содержимому исходников.
Если после сборки менялась только документация, повторная сборка не обязательна;
в CHANGELOG сохраняются фактические коммит и время из APK.

## Подготовлен выпуск 0.3.29

- [Описание для GitHub Release](0.3.29.md).
- [Изменения и проверки](../../CHANGELOG.md#0329---2026-10-25).
- APK `hwlivetest-0.3.29-debug.apk`: версия 0.3.29, коммит `35cb12ce`,
  сборка 25.10.2026 15:22:54 UTC. После сборки менялась только документация.

Для подготовленной ветки команды PowerShell 7 (переподпись выполняется вручную):

```powershell
git fetch origin
git switch codex/polling-experiment
git rebase --force-rebase origin/main
git push --force-with-lease -u origin codex/polling-experiment
```

В [Actions](https://github.com/ViacheslavMezentsev/hwlivetest-android/actions) дождаться
успеха Android CI и Docs для последнего коммита ветки.
Pages проверяется при pull request и публикуется после переноса изменений в `main`;
перед отправкой локально выполнить `python tools/check_site.py`.
При установленном GitHub CLI можно проверить запуски:

```powershell
gh run list --branch codex/polling-experiment --commit (git rev-parse HEAD)
```

После успешного CI:

```powershell
git fetch origin
if ((git rev-parse HEAD) -ne (git rev-parse origin/codex/polling-experiment)) { throw "Ветка отличается от проверенной на GitHub" }
git land codex/polling-experiment
```

После переноса проверенной ветки в `main`:

```powershell
git switch main
git pull --ff-only origin main
git tag -s v0.3.29 -m "HW Live Test 0.3.29"
git push origin v0.3.29
```

Затем на GitHub открыть **Releases → Draft a new release**, выбрать существующий
`v0.3.29`, вставить описание из `0.3.29.md` и приложить APK. SHA-256 файла должен
совпадать с указанным в описании. При установленном GitHub CLI:

```powershell
gh release create v0.3.29 ./claude-patches/hwlivetest-0.3.29-debug.apk --verify-tag --title "HW Live Test 0.3.29" --notes-file docs/releases/0.3.29.md
```
