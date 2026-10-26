# Начало работы

SDK хранит активный набор флагов в памяти. Приложение объявляет типизированные определения, загружает JSON через `FeatureFlagsSystem.manager` и передаёт потребителям `FeatureFlagsReader`. Чтение синхронное; подписки на изменения доступны через Flow.

## Подключение из исходников

Опубликованных Maven-артефактов пока нет. Для подключения клонируй SDK рядом с приложением и добавь composite build в `settings.gradle.kts` приложения:

```kotlin
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

includeBuild("../feature-flags") {
    dependencySubstitution {
        substitute(module("local.featureflags:core")).using(project(":feature-flags-core"))
        substitute(module("local.featureflags:json")).using(project(":feature-flags-json"))
        substitute(module("local.featureflags:retrofit")).using(project(":feature-flags-retrofit"))
        substitute(module("local.featureflags:android")).using(project(":feature-flags-android"))
    }
}
```

В существующем проекте объедини настройки репозиториев с уже объявленными блоками. `local.featureflags:*` здесь - локальные алиасы для substitution, а не координаты опубликованных библиотек. Путь `../feature-flags` должен вести к клону SDK. Composite build сохраняет его собственные `buildSrc` и version catalog. Механизм подключения описан в [документации Gradle](https://docs.gradle.org/current/userguide/composite_builds.html).

В `build.gradle.kts` модуля приложения добавь:

```kotlin
dependencies {
    implementation("local.featureflags:json")
}
```

Модуль `json` транзитивно предоставляет `core`. Для HTTP добавь `implementation("local.featureflags:retrofit")`, для Android assets и дискового кэша - `implementation("local.featureflags:android")`. Если реализуешь свой codec, достаточно `core`.

Исходная сборка использует JDK 21, Kotlin 2.4.21 и JVM target 17. Android-модуль рассчитан на API 21+ и собирается с SDK Platform 36 / Build Tools 36.0.0. Полная проверка репозитория собирает Android-модуль и требует Android SDK. Актуальные версии находятся в [version catalog](../gradle/libs.versions.toml).

## Первый флаг

Это самостоятельный JVM-пример. Источник возвращает JSON из строки; вместо него можно передать HTTP-адаптер или собственную suspend-функцию загрузки.

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
    val source = FeatureFlagsSource {
        """{"featureToggles":[{"feature":"dashboard.guestMode","enabled":true}]}"""
    }
    val system = FeatureFlagsSystem(
        remote = source,
        codec = JsonConfigurationCodec,
        fallback = FallbackPolicy.Never,
    )

    check(!system.reader.get(guestMode))

    when (val result = system.manager.refresh()) {
        is RefreshResult.Applied -> check(system.reader.get(guestMode))
        is RefreshResult.Failure -> error("Configuration was not applied: ${result.error::class.simpleName}")
    }
}
```

До `refresh()` действует default определения. Конструктор системы, `get()` и подписка `observe()` не загружают данные. Успешный refresh целиком заменяет набор: флаг, которого нет в новом документе, снова использует default.

## Как распределить ответственность

Создавай одну систему на область конфигурации и передавай её `reader` фичам. Владелец системы выбирает источники, codec и fallback, вызывает `refresh()` в своей coroutine и обрабатывает результат. SDK не запускает фоновые задания, не выбирает DI-контейнер и не определяет момент старта приложения.

Дальнейшие темы:

- [Определения и параметры](flags-and-parameters.md) - Boolean, типизированные настройки и defaults.
- [Загрузка и наблюдение](loading-and-observation.md) - Flow, readiness, ошибки и отмена.
- [Форматы JSON](json-format.md) - контракт ответа и строгая валидация.
- [Android и Retrofit](android-integration.md) - сеть, assets и жизненный цикл.
- [Постоянный кэш](cache.md) - запуск из кэша, TTL и смена сессии.
- [Процентная раскатка](percentage-rollout.md) - правила v2 и контекст пользователя.

[README](../README.md)
