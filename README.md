# Feature Flags SDK

Типобезопасный SDK фича-тоглов для Kotlin и Android. Ядро работает на JVM без Android SDK.

Реализованы определения флагов, атомарные снимки, Reader с Flow и readiness, JSON codec, ручная загрузка с явной политикой fallback и адаптеры Retrofit/Android assets. Опубликованных артефактов нет.

## Модули

- `feature-flags-core` - определения, параметры, `Configuration`, `ConfigurationCodec`, `FeatureFlagsReader`, `Readiness` и загрузка через `FeatureFlagsSystem`. Использует Kotlin и coroutines; не зависит от Android и JSON-библиотек.
- `feature-flags-json` - полный ответ через `JsonConfigurationCodec.decode` и отдельные параметры JSON object/null через `JsonParameters.decode`. Строки, дроби, экспоненциальная запись и переполнение не преобразуются в `Int`, а дают default параметра.
- `feature-flags-retrofit` - JVM-источник `RetrofitFeatureFlagsSource`. Получает suspend-функцию запроса приложения без привязки к endpoint и заголовкам.
- `feature-flags-android` - `AssetsFeatureFlagsSource` для UTF-8 файлов из assets, Android API 21+.

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

## Retrofit и Android assets

```kotlin
import android.content.res.AssetManager
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.android.AssetsFeatureFlagsSource
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec
import io.github.avtobot22.featureflags.retrofit.RetrofitFeatureFlagsSource
import okhttp3.ResponseBody
import retrofit2.Response

fun createAndroidFlags(
    request: suspend () -> Response<ResponseBody>,
    assets: AssetManager,
): FeatureFlagsSystem =
    FeatureFlagsSystem(
        remote = RetrofitFeatureFlagsSource(request),
        codec = JsonConfigurationCodec,
        fallback = FallbackPolicy.OnRemoteEmpty,
        local = AssetsFeatureFlagsSource(assets),
    )
```

Приложение добавляет `android.permission.INTERNET` и файл `src/main/assets/FallbackFeatureToggles.json`. Имя или путь внутри assets можно передать вторым аргументом источника. SDK не поставляет локальную конфигурацию; отсутствующий файл даёт `LocalSource`, невалидный JSON - `InvalidConfiguration(BUNDLED_ASSET, cause)`.

В `request` передаётся вызов своего Retrofit-метода, например `api::getFlags`, возвращающего `Response<ResponseBody>`. Приложение определяет HTTP-метод, URL и заголовки. Адаптер принимает успешный ответ 2xx с телом; ответы без тела, включая HTTP 204/205, дают ошибку источника. Пустое или невалидное тело при HTTP 200 проверяет codec. JSON converter для Retrofit не требуется.

Приложение настраивает таймауты, retry и авторизацию в своём Retrofit/OkHttp клиенте. Для отмены во время получения тела Retrofit-метод должен возвращать буферизованный `ResponseBody` без `@Streaming`. Оба источника выполняют блокирующее чтение на IO и закрывают ресурсы; отмена распространяется вызывающему коду.

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

Нужны JDK 21 и Android SDK Platform 36 с Build Tools 36.0.0. Путь к SDK задаётся через `ANDROID_HOME` или `sdk.dir` в локальном `local.properties`.

```sh
./gradlew qualityCheck :buildSrc:validatePlugins
python3 scripts/check_repository.py
```

Команда выполняет статический анализ, JVM-тесты, Android lint, сборку библиотек и тестового APK, проверку convention plugins. Отчёты находятся в `build/reports/` корня и соответствующих модулей. В Windows используй `gradlew.bat`.

На подключённом Android-устройстве или эмуляторе отдельно запускаются проверки чтения APK assets:

```sh
./gradlew :feature-flags-android:connectedDebugAndroidTest
```

Скрипт проверки файлов читает пути из индекса Git; новые файлы сначала добавь в индекс. Для проверки workflow нужен [actionlint](https://github.com/rhysd/actionlint):

```sh
actionlint
```

CI выполняет эти проверки, запускает instrumented tests на эмуляторе и проверяет заголовок PR.

## Разработка

Изменения принимаются через PR в `main`, с объединением через squash. Заголовок PR и сообщения коммитов - на английском в формате Conventional Commits, например `feat(core): add flag definitions`.
