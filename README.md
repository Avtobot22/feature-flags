# Feature Flags SDK

Типобезопасный SDK фича-тоглов для Kotlin и Android. Ядро планируется независимым от Android; загрузка конфигурации подключается через адаптеры.

Сейчас подготовлен репозиторий. Реализация SDK и Gradle-сборка появятся в следующих PR.

## Разработка

Изменения принимаются через PR в `main`, с объединением через squash. Заголовок PR и сообщения коммитов - на английском в формате Conventional Commits, например `feat(core): add flag definitions`.

Локальная проверка файлов, добавленных в Git:

```sh
python3 scripts/check_repository.py
```

Для проверки GitHub Actions нужен [actionlint](https://github.com/rhysd/actionlint):

```sh
actionlint
```

CI выполняет эти проверки и проверяет заголовок PR. Сборка и тесты SDK будут добавлены вместе с кодом библиотеки.
