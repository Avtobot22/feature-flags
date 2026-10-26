# Загрузка, чтение и наблюдение

`FeatureFlagsSystem` связывает источники, codec, хранилище активного снимка и два публичных интерфейса: `manager` обновляет конфигурацию, `reader` читает её. Приложение владеет системой и coroutine, в которой выполняется загрузка.

## Источник и политика fallback

`FeatureFlagsSource` - suspend-функция `loadJson(): String`, возвращающая полный документ. Собственный источник отвечает за I/O и переключение контекста при блокирующих операциях. Готовые [Retrofit/Android-адаптеры](android-integration.md) делают это сами.

```kotlin
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec

fun createFlags(remote: FeatureFlagsSource, bundled: FeatureFlagsSource): FeatureFlagsSystem =
    FeatureFlagsSystem(
        remote = remote,
        codec = JsonConfigurationCodec,
        fallback = FallbackPolicy.OnRemoteEmpty,
        local = bundled,
    )
```

Политика обязательна:

| Политика | Валидный непустой remote | Валидный пустой remote | Ошибка remote |
|---|---|---|---|
| `Never` | Применить remote | Применить пустой набор | Сохранить прежний снимок |
| `OnRemoteEmpty` | Применить remote | Загрузить и применить local | Сохранить прежний снимок |

`OnRemoteEmpty` требует `local` в конструкторе. Это fallback по пустому ответу, а не по сетевой ошибке. Оба источника проверяет один codec. Валидный local, включая пустой, полностью заменяет набор. Конфигурации не объединяются с remote или предыдущим снимком. Поведение при сетевой ошибке можно дополнить отдельной [политикой кэша](cache.md).

## Результат обновления

`manager.refresh()` выполняет полную попытку загрузки и возвращает:

- `RefreshResult.Applied(revision, origin, flagCount, age, cacheWrite)` - новый снимок уже опубликован;
- `RefreshResult.Failure(error)` - попытка не применила конфигурацию, прежний снимок сохранён.

`origin` принимает `REMOTE`, `BUNDLED_ASSET` или `CACHE`. `revision` - локальный номер успешной публикации. Он увеличивается и для пустых или равных наборов; это не версия ответа сервера. `age` и `cacheWrite` относятся к [кэшу](cache.md).

| `RefreshError` | Причина |
|---|---|
| `Transport(cause)` | Исключение при чтении remote, включая HTTP-ошибку адаптера |
| `LocalSource(cause)` | Исключение выбранного local-источника |
| `InvalidConfiguration(origin, cause)` | Codec отверг документ через `IllegalArgumentException` |
| `Invalidated` | Система с кэшем закрыта через `invalidate()` |

Другие исключения пользовательского codec и ошибки его контракта распространяются вызывающему коду. Codec должен возвращать неизменяемую конфигурацию, пригодную для параллельного чтения, с неотрицательным `flagCount` и согласованным `isEmpty`. При ошибке валидации весь документ отклоняется: частичного применения нет.

Причины ошибок могут содержать тело ответа, URL или приватные данные. Для обычной диагностики используй категорию ошибки и безопасные метаданные; `cause` требует очистки перед логированием.

## Синхронное чтение

`reader.get(definition)` оценивает один текущий снимок без I/O. До первой публикации и для отсутствующего ключа действует default определения. Два последовательных вызова `get()` могут увидеть разные снимки, если между ними завершилось обновление; общей транзакции для нескольких вызовов нет.

При прямом использовании `Configuration` можно разобрать документ через codec и вызвать `configuration.evaluate(definition)`. Разбор и оценка не публикуют этот документ в систему.

## Flow и состояния

```kotlin
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsReader
import kotlinx.coroutines.flow.Flow

private val guestMode = Feature.boolean("dashboard.guestMode", default = false)

fun observeGuestMode(reader: FeatureFlagsReader): Flow<Boolean> = reader.observe(guestMode)
```

`observe()` выдаёт текущее значение и последующие результаты, отличающиеся по `equals`. Промежуточные снимки могут пропускаться. Это поток состояния, поэтому он не предназначен для аудита каждого обновления. Подписка не запускает загрузку. Ошибка определения или decoder завершает только соответствующий collector и не подменяет активный документ.

Два `StateFlow` отвечают на разные вопросы:

| Поток | Значения | Назначение |
|---|---|---|
| `reader.readiness` | `NotInitialized`, `Ready(revision, origin, age)` | Есть ли успешно опубликованный снимок |
| `manager.refreshStatus` | `Idle`, `Running`, `Finished(result)` | Как завершилась или выполняется последняя попытка refresh |

`Ready` возможен для пустого набора. Во время неудачного обновления ранее готовый reader остаётся `Ready`; ошибка доступна через результат или `refreshStatus`. Успешное явное восстановление кэша меняет readiness, но не `refreshStatus`.

## Конкурентность и отмена

Конкурентные вызовы `refresh()` выполняются последовательно. С кэшем `restore()` использует ту же очередь. Читатели продолжают работать с прежним снимком, пока новый документ загружается и проверяется; при публикации они видят целиком старую либо целиком новую конфигурацию.

`CancellationException` распространяется вызывающему коду. До публикации отмена сохраняет прежний снимок. После публикации новый снимок уже активен, даже если coroutine была отменена до возврата из `refresh()` или во время записи кэша. Отмена выполняющегося обновления и неожиданное исключение codec возвращают `refreshStatus` в `Idle`; отмена ожидающего вызова не меняет статус текущего обновления.

Приложение выбирает время повторной загрузки, таймауты, retry и scope. SDK не создаёт собственных coroutine и не выполняет polling. Для Android привязывай сбор Flow к жизненному циклу экрана, а общую систему - к области приложения или сессии: [пример интеграции](android-integration.md).

[README](../README.md)
