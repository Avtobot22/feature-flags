# Android и Retrofit

На Android используются те же определения и reader, что на JVM. Дополнительный модуль предоставляет чтение UTF-8 assets и дисковый кэш; HTTP-адаптер находится в отдельном JVM-модуле.

Добавь к `json` зависимости `local.featureflags:retrofit` и `local.featureflags:android` после настройки [подключения из исходников](getting-started.md). Android-модуль поддерживает API 21+. В manifest приложения для сетевых запросов нужен permission:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Создание системы

Retrofit-метод приложения должен возвращать `Response<ResponseBody>`. Endpoint ниже приведён для примера: URL, заголовки, авторизация, таймауты и retry принадлежат приложению.

```kotlin
import android.content.Context
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.android.AssetsFeatureFlagsSource
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec
import io.github.avtobot22.featureflags.retrofit.RetrofitFeatureFlagsSource
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET

interface FlagsApi {
    @GET("feature-toggles")
    suspend fun getFlags(): Response<ResponseBody>
}

fun createAndroidFlags(context: Context, api: FlagsApi): FeatureFlagsSystem =
    FeatureFlagsSystem(
        remote = RetrofitFeatureFlagsSource(api::getFlags),
        codec = JsonConfigurationCodec,
        fallback = FallbackPolicy.OnRemoteEmpty,
        local = AssetsFeatureFlagsSource(context.applicationContext.assets),
    )
```

Для `ResponseBody` JSON converter Retrofit не требуется. Не помечай метод `@Streaming`: адаптер ожидает буферизованный отменяемый Retrofit-запрос. Он читает тело на IO и закрывает body/errorBody после попытки.

Успешный HTTP 2xx должен содержать тело. Неуспешный статус и отсутствие тела, включая HTTP 204/205, дают ошибку источника `Transport`. Пустое или невалидное тело HTTP 200 отвергает codec как `InvalidConfiguration`. Отмена распространяется вызывающему коду.

## Файл assets

Положи локальную конфигурацию в `src/main/assets/FallbackFeatureToggles.json` модуля приложения:

```json
{
  "featureToggles": [
    {"feature": "dashboard.guestMode", "enabled": false}
  ]
}
```

Имя можно изменить вторым аргументом `AssetsFeatureFlagsSource(assets, "flags/fallback.json")`. Путь задаётся относительно assets. SDK не поставляет файл по умолчанию.

При `OnRemoteEmpty` asset читается только после валидного пустого remote. Ошибка сети не переключает загрузку на assets. Отсутствующий файл даёт `LocalSource`; неверный JSON - `InvalidConfiguration` с origin `BUNDLED_ASSET`. До успешной загрузки сохраняются прежние значения или defaults. Подробнее: [загрузка и fallback](loading-and-observation.md).

## Владение и запуск

Создай систему один раз в контейнере приложения или сессии. Передавай feature-модулям `FeatureFlagsReader`, а `manager` оставь владельцу загрузки. Не создавай отдельную систему в каждом экране или при каждой recomposition: у них будут независимые снимки.

Для запуска используй coroutine scope приложения или сессии, время жизни которого определяет приложение. Если старт должен дождаться флагов, вызови `refresh()` в стартовом сценарии и обработай `Applied`/`Failure`; если defaults допустимы, экран может читать их сразу и подписываться на изменения. Конструктор системы ничего не загружает.

## Подписка экрана

SDK не зависит от Lifecycle или Compose. В Android-экране сбор можно привязать к `STARTED` через AndroidX Lifecycle; соответствующие зависимости добавляет приложение:

```kotlin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val guestMode = Feature.boolean("dashboard.guestMode", default = false)

fun LifecycleOwner.observeGuestMode(
    reader: FeatureFlagsReader,
    render: (Boolean) -> Unit,
): Job = lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        reader.observe(guestMode).collect { enabled -> render(enabled) }
    }
}
```

Для Fragment вызывай этот код на `viewLifecycleOwner`. При каждом новом сборе Flow выдаёт текущее значение; повторная подписка не выполняет HTTP-запрос. Если используется Compose, приложение может собирать Flow через свой lifecycle-aware механизм; SDK не требует конкретного UI-подхода.

Постоянный кэш подключается отдельно через `CacheSettings` и `AtomicFileConfigurationCache`: [настройка кэша](cache.md). Для процентной раскатки передавай экрану reader, привязанный к контексту сессии: [контекст и смена пользователя](percentage-rollout.md).

[README](../README.md)
