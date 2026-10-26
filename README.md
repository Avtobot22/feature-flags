# Feature Flags SDK

Типобезопасные фича-флаги для Kotlin и Android. Приложение объявляет флаги и их дефолты в коде, загружает JSON-конфигурацию и читает значения из памяти. Для изменений доступны `Flow`, для распределения по аудитории - стабильная процентная раскатка.

SDK поддерживает ручное обновление, локальную конфигурацию из Android assets и постоянный кэш. Ядро работает на JVM и не зависит от Android. Опубликованных артефактов пока нет; [подключение из исходников](docs/getting-started.md) описано отдельно.

## Модули

| Модуль | Назначение |
|---|---|
| `feature-flags-core` | Определения, чтение и наблюдение, загрузка, политики fallback и кэша, процентная раскатка |
| `feature-flags-json` | Проверка JSON-конфигураций и чтение параметров |
| `feature-flags-retrofit` | Сетевой источник через Retrofit-запрос приложения |
| `feature-flags-android` | Чтение assets и хранение кэша через `AtomicFile`, Android API 21+ |

## Первый пример

После [подключения `core` и `json`](docs/getting-started.md) этот JVM-пример можно запустить с `main()`. Для наглядности источник возвращает JSON из строки; в приложении его можно заменить [Retrofit-источником](docs/android-integration.md).

```kotlin
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.RefreshResult
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val guestMode = Feature.boolean("dashboard.guestMode", default = false)
    val system = FeatureFlagsSystem(
        remote = FeatureFlagsSource {
            """{"featureToggles":[{"feature":"dashboard.guestMode","enabled":true}]}"""
        },
        codec = JsonConfigurationCodec,
        fallback = FallbackPolicy.Never,
    )

    println(system.reader.get(guestMode)) // false: используется default

    when (system.manager.refresh()) {
        is RefreshResult.Applied -> println(system.reader.get(guestMode)) // true
        is RefreshResult.Failure -> println("Конфигурация не обновлена")
    }
}
```

Создание `FeatureFlagsSystem` и подписка на `observe()` не запускают загрузку. Владелец системы вызывает `manager.refresh()` в своей coroutine, а потребителям передаёт `reader`. На Android обновление запускается из scope приложения или другого владельца жизненного цикла; `runBlocking` из примера предназначен для JVM.

Чтение синхронное, без I/O. Успешное обновление целиком заменяет активный снимок; отсутствующие флаги используют свои дефолты. Ошибка загрузки сохраняет прежнюю конфигурацию. Отмена распространяется как `CancellationException`; если публикация уже состоялась, новый снимок остаётся активным.

## Документация

- [Подключение и первый запуск](docs/getting-started.md) - Gradle, выбор модулей и минимальная интеграция.
- [Флаги и параметры](docs/flags-and-parameters.md) - boolean, типизированные параметры, декодеры и дефолты.
- [Загрузка и наблюдение](docs/loading-and-observation.md) - `refresh`, fallback, ошибки, `Flow` и готовность.
- [Формат JSON](docs/json-format.md) - исходная схема, версия 2 и правила валидации.
- [Android и Retrofit](docs/android-integration.md) - сетевой запрос, assets и жизненный цикл системы.
- [Постоянный кэш](docs/cache.md) - режимы загрузки, TTL, восстановление и очистка.
- [Процентная раскатка](docs/percentage-rollout.md) - доля аудитории, контекст и стабильность распределения.

## Сборка и проверки

Нужны JDK 21, Android SDK Platform 36 и Build Tools 36.0.0. Путь к Android SDK задаётся через `ANDROID_HOME` или `sdk.dir` в локальном `local.properties`. Версии зависимостей закреплены в [version catalog](gradle/libs.versions.toml).

```sh
./gradlew qualityCheck :buildSrc:validatePlugins
python3 scripts/check_repository.py
actionlint
```

`qualityCheck` запускает статический анализ, JVM-тесты, Android lint и сборку библиотек и тестового APK. Отчёты находятся в `build/reports/` корня и модулей. Скрипт проверки файлов читает пути из индекса Git; новые файлы сначала добавь в индекс. Для workflow нужен [actionlint](https://github.com/rhysd/actionlint). В Windows используй `gradlew.bat`.

На подключённом Android-устройстве или эмуляторе:

```sh
./gradlew :feature-flags-android:connectedDebugAndroidTest
```

CI выполняет эти проверки, включая instrumented tests на эмуляторе. Изменения принимаются через PR в `main`, с объединением через squash. Сообщения коммитов и заголовок PR - на английском в формате Conventional Commits, например `docs(sdk): add usage guides`.
