# Feature Flags SDK

Типобезопасный SDK фича-тоглов для Kotlin и Android. Ядро работает на JVM без Android SDK.

Реализованы определения флагов, чтение JSON-параметров и этап I2: атомарная полная замена активного снимка, синхронный Reader, наблюдение за значениями и readiness. Store и запись остаются внутренними; публичная сборка системы, codec полного ответа и загрузка конфигурации ещё не реализованы. Опубликованных артефактов нет.

## Модули

- `feature-flags-core` - определения, параметры, `FeatureFlagsReader`, `Readiness` и `ConfigOrigin`. Использует Kotlin и coroutines; не зависит от Android и JSON-библиотек.
- `feature-flags-json` - чтение JSON object/null через `JsonParameters.decode`. Строки, дроби, экспоненциальная запись и переполнение не преобразуются в `Int`, а дают default параметра.

Общие настройки сборки находятся в `buildSrc`, версии - в [version catalog](gradle/libs.versions.toml). Зависимости между модулями задаются через типизированные `projects.*`.

## Чтение флагов

Код потребителя получает `FeatureFlagsReader` от владельца конфигурации. Пример объявляет определение и читает его через переданный Reader:

```kotlin
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsReader
import kotlinx.coroutines.flow.Flow

val guestMode = Feature.boolean("dashboard.guestMode", default = false)

fun isGuestModeEnabled(flags: FeatureFlagsReader): Boolean = flags.get(guestMode)

fun observeGuestMode(flags: FeatureFlagsReader): Flow<Boolean> = flags.observe(guestMode)
```

До первой публикации и при отсутствии ключа Reader возвращает defaults определения. `get` читает один снимок без I/O; два отдельных вызова не образуют транзакцию. Выключенный configured-флаг сохраняет декодированные параметры. Ошибки decoder распространяются вызывающему коду.

`observe` выдаёт текущее значение и дальнейшие изменения по `equals`. Равные значения фильтруются, промежуточные снимки могут пропускаться. `readiness.value` сразу отражает `NotInitialized` или `Ready(revision, origin)`; revision увеличивается при каждой успешной публикации, включая пустые и равные наборы. Подписка не запускает загрузку.

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
