# Feature Flags SDK

Типобезопасный SDK фича-тоглов для Kotlin и Android. Ядро работает на JVM без Android SDK.

Реализованы определения флагов, атомарные снимки, Reader с Flow и readiness, JSON codec и ручная загрузка с явной политикой fallback. Android assets и HTTP-адаптеры ещё не реализованы. Опубликованных артефактов нет.

## Модули

- `feature-flags-core` - определения, параметры, `Configuration`, `ConfigurationCodec`, `FeatureFlagsReader`, `Readiness` и загрузка через `FeatureFlagsSystem`. Использует Kotlin и coroutines; не зависит от Android и JSON-библиотек.
- `feature-flags-json` - полный ответ через `JsonConfigurationCodec.decode` и отдельные параметры JSON object/null через `JsonParameters.decode`. Строки, дроби, экспоненциальная запись и переполнение не преобразуются в `Int`, а дают default параметра.

Общие настройки сборки находятся в `buildSrc`, версии - в [version catalog](gradle/libs.versions.toml). Зависимости между модулями задаются через типизированные `projects.*`.

## Загрузка конфигурации

Приложение передаёт источники через `FeatureFlagsSource.loadJson(): String`. Источник отвечает за I/O и переключение контекста для блокирующих операций; один codec проверяет оба документа.

```kotlin
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec

fun createFlags(remote: FeatureFlagsSource, local: FeatureFlagsSource): FeatureFlagsSystem =
    FeatureFlagsSystem(
        remote = remote,
        codec = JsonConfigurationCodec,
        fallback = FallbackPolicy.OnRemoteEmpty,
        local = local,
    )
```

Создание системы не запускает загрузку. Владелец вызывает `system.manager.refresh()` в своей coroutine и обрабатывает `RefreshResult.Applied` либо `Failure`; feature-модули получают `system.reader`.

Политика обязательна: `Never` применяет и пустую remote-конфигурацию, `OnRemoteEmpty` требует local и обращается к нему только после валидного пустого remote. Ошибки remote не запускают fallback. Валидный local, включая пустой, полностью заменяет набор; источники и предыдущий снимок не объединяются.

Конкурентные `refresh()` выполняются последовательно. `Applied` содержит revision, origin и число флагов. `refreshStatus` показывает `Idle`, `Running` или `Finished(result)` отдельно от readiness. Ошибки источников дают `Transport` или `LocalSource`, ошибки codec по `IllegalArgumentException` - `InvalidConfiguration(origin, cause)`. Произвольные ошибки codec распространяются вызывающему коду. Causes могут содержать приватные данные, поэтому их нельзя автоматически писать в логи.

Ошибка загрузки сохраняет активный снимок. Отмена распространяется как `CancellationException`: до публикации она сохраняет прежнее состояние, после атомарной публикации новый снимок уже активен. При отмене выполняющегося обновления или неожиданной ошибке codec статус возвращается в `Idle`; отмена ожидающего вызова не меняет статус текущего обновления.

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

## Разбор конфигурации

```kotlin
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec

val configuration = JsonConfigurationCodec.decode(
    """{"featureToggles":[{"feature":"dashboard.guestMode","enabled":true}]}""",
)
val enabled = configuration.evaluate(guestMode)
```

Codec проверяет весь документ и возвращает неизменяемую `Configuration`: `flagCount` содержит число различных ключей, `isEmpty` отличает успешный пустой набор. `evaluate` использует те же определения и defaults, что Reader; ошибки decoder распространяются вызывающему коду. Разбор не публикует снимок и не запускает загрузку. Собственный codec должен возвращать неизменяемую, безопасную для параллельного чтения конфигурацию с неотрицательным `flagCount` и согласованным `isEmpty`.

`featureToggles`, строковый `feature` и Boolean `enabled` обязательны. `parameters` допускает объект, `null` или отсутствие. Неверная схема, синтаксическая ошибка и повторяющиеся ключи флагов дают `IllegalArgumentException`. Регистр ключей значим. Неизвестные флаги и параметры сохраняются, дополнительные поля ответа и флага игнорируются.

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
