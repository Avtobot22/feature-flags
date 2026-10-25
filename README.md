# Feature Flags SDK

Типобезопасный SDK фича-тоглов для Kotlin и Android. Ядро работает на JVM без Android SDK.

Реализован первый этап: определения Boolean-флагов и флагов с параметрами, явные значения по умолчанию и строгое чтение JSON-параметров. Активное хранилище, подписки и загрузка конфигурации будут добавлены следующими этапами.

## Модули

- `feature-flags-core` - `FlagDefinition`, `Feature`, `EvaluationInput`, `FeatureValue`, `ParametersDecoder` и `ParameterReader`. Не зависит от Android и JSON-библиотек.
- `feature-flags-json` - чтение JSON object/null через `JsonParameters.decode`. Строки, дроби, экспоненциальная запись и переполнение не преобразуются в `Int`, а дают default параметра.

Общие настройки сборки находятся в `buildSrc`, версии - в [version catalog](gradle/libs.versions.toml). Зависимости между модулями задаются через типизированные `projects.*`.

## Сборка и проверки

Нужен JDK 21. Android SDK для этих модулей не требуется.

```sh
./gradlew qualityCheck :buildSrc:validatePlugins
python3 scripts/check_repository.py
```

Команда выполняет статический анализ, тесты, сборку библиотек и проверку convention plugins. Отчёты находятся в `build/reports/` корня и соответствующих модулей. В Windows используй `gradlew.bat`.

Скрипт проверки файлов читает пути из индекса Git; новые файлы сначала добавь в индекс. Для проверки workflow нужен [actionlint](https://github.com/rhysd/actionlint):

```sh
actionlint
```

CI выполняет эти проверки и проверяет заголовок PR.

## Разработка

Изменения принимаются через PR в `main`, с объединением через squash. Заголовок PR и сообщения коммитов - на английском в формате Conventional Commits, например `feat(core): add flag definitions`.
