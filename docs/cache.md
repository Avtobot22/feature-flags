# Постоянный кэш

Кэш позволяет восстановить последний сохранённый remote-документ после перезапуска или при сетевой ошибке. Когда разрешено такое восстановление, задаёт приложение через `CacheSettings`. Без этих настроек система не обращается к диску, а `system.cache` равен `null`.

## Настройка Android-хранилища

```kotlin
import android.content.Context
import io.github.avtobot22.featureflags.CacheMode
import io.github.avtobot22.featureflags.CacheSettings
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.android.AtomicFileConfigurationCache
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec
import java.io.File

fun createCachedFlags(context: Context, remote: FeatureFlagsSource): FeatureFlagsSystem =
    FeatureFlagsSystem(
        remote = remote,
        codec = JsonConfigurationCodec,
        fallback = FallbackPolicy.Never,
        cache = CacheSettings(
            storage = AtomicFileConfigurationCache(
                File(context.applicationContext.noBackupFilesDir, "feature-flags"),
            ),
            scope = "production/flags-v1/anonymous",
            ttlMillis = 60_000,
            maxAgeMillis = 300_000,
            mode = CacheMode.NETWORK_FIRST,
        ),
    )
```

Здесь 1 минута TTL и 5 минут максимального возраста - пример политики приложения. Требуется `0 <= ttlMillis <= maxAgeMillis`. По умолчанию `maxAgeMillis=ttlMillis`, то есть stale не разрешён; режим по умолчанию - `NETWORK_FIRST`.

`scope` - область ответа: строка не должна быть пустой или состоять только из пробелов. Включай в неё окружение, endpoint, версию codec и все параметры контекста, влияющие на ответ. Не включай токены и персональные данные. Одна живая система владеет парой storage/scope; несколько систем или процессов не должны одновременно использовать одну область.

## Выбор режима

| Режим | Действие `refresh()` |
|---|---|
| `NETWORK_FIRST` | Запросить remote; при ошибке источника попробовать кэш до `maxAgeMillis` |
| `CACHE_FIRST` | Применить свежий кэш до TTL без remote; иначе запросить remote и при ошибке источника попробовать кэш до `maxAgeMillis` |
| `REMOTE_ONLY` | Запросить только remote; автоматического чтения кэша нет |

Ошибка схемы remote не запускает cache fallback. В `CACHE_FIRST` свежий кэш позволяет вообще не обращаться к remote; серверное изменение станет известно после следующего запроса. Если кэш при сетевой ошибке недоступен, возвращается исходный `Transport`.

Политика `OnRemoteEmpty` относится только к новому валидному пустому remote. Пустой набор из кэша публикуется целиком с origin `CACHE` и не запускает assets. Конфигурации не объединяются.

## Явное восстановление перед запросом

`restore()` доступен в любом режиме, включая `REMOTE_ONLY`. Он проверяет запись до `maxAgeMillis` и не обращается к remote или assets. Например, старт может сначала восстановить снимок, затем запросить обновление:

```kotlin
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.RefreshResult

suspend fun startFlags(system: FeatureFlagsSystem): RefreshResult {
    val cache = requireNotNull(system.cache)
    cache.restore()
    return system.manager.refresh()
}
```

В этом примере недоступный кэш не блокирует сетевую попытку. `CacheRestoreResult.Applied.result` содержит revision, origin `CACHE`, число флагов и возраст. `CacheRestoreResult.Unavailable.reason` различает `Missing`, `Expired`, `ClockRollback`, `Incompatible`, `Invalidated` и `Failure(cause)`. Повреждённый или несовместимый документ не публикуется. Неудачное восстановление сохраняет прежний снимок.

`restore()` меняет `readiness` при успехе, но не `refreshStatus`. Вызовы `restore()` и `refresh()` выполняются последовательно.

## Возраст и TTL

Возраст вычисляется по epoch millis с помощью `CacheClock`; для тестов приложение может передать свои часы. Возраст `<= ttlMillis` считается `FRESH`, `ttlMillis < возраст <= maxAgeMillis` - `STALE`. Более старая запись отклоняется. Будущая метка после перевода часов назад тоже отклоняется; перевод часов вперёд может досрочно состарить запись.

`Readiness.Ready.age` и `RefreshResult.Applied.age` описывают возраст на момент публикации. У assets `age=null`. Таймеров нет: TTL ограничивает повторное чтение кэша, но не снимает уже активный снимок и не обновляет его метаданные. Приложение само решает, как долго читать эти флаги во время offline-сессии.

## Что сохраняется

В кэш записывается исходный валидный remote-документ после успешной публикации. Он заново проверяется текущим codec при каждом восстановлении; `Configuration` не сериализуется.

Если пустой remote выбрал assets, в кэш сохраняется пустой remote, а не assets. Если выбранный asset не удалось прочитать или проверить, прежние память и кэш сохраняются. Применение записи из кэша не продлевает её возраст и не перезаписывает её.

Публикация в память предшествует записи на диск. `RefreshResult.Applied.cacheWrite` сообщает:

- `Saved` - запись завершилась;
- `Failure(cause)` - память обновлена, сохранить документ не удалось;
- `NotRequested` - запись не запрашивалась, например при чтении из кэша или отключённом кэше.

Ошибка записи не откатывает память и не превращает `Applied` в `RefreshResult.Failure`. При отмене после публикации новый снимок остаётся активным, а диск может содержать прежнюю либо новую полную запись. Причины ошибок хранилища могут содержать приватные данные; не отправляй их в логи без очистки.

## Logout и смена области

Перед повторным использованием персонализированной области вызови `requireNotNull(system.cache).invalidate()` и дождись `CacheInvalidationResult.Cleared`. Эта операция навсегда закрывает старую систему и сразу сбрасывает её память в `NotInitialized`; чтение возвращает defaults. Поздний remote-запрос не сможет опубликовать данные или заполнить кэш. Для следующего контекста создай новую систему.

Если запись на диск уже началась, очистка ждёт её завершения и удаляет запись после неё. `Failure` удаления и отмена не гарантируют очистку диска: повтори `invalidate()` на старой системе, прежде чем снова подключать эту область. Система остаётся закрытой независимо от результата удаления. SDK не отменяет родительский Job сетевого запроса; ненужную coroutine отменяет приложение.

## Хранилище и защита данных

`AtomicFileConfigurationCache` выполняет I/O на IO, хранит записи отдельно по scope, использует checksum и атомарную замену файла. Лимит документа по умолчанию - 1 MiB; его можно изменить параметром `maxDocumentBytes`. Повреждённая запись не восстанавливается. Checksum обнаруживает повреждение, но не заменяет подпись или шифрование. Хранилище не шифруется: конфигурация не должна содержать секреты.

Для другого хранилища можно реализовать порт `ConfigurationCache` с `read(scope)`, `write(CacheRecord)` и `clear(scope)`. Реализация должна изолировать области, атомарно заменять полную запись и поддерживать отмену операций. `CacheRecord.formatVersion` относится к формату записи кэша, а не к JSON schemaVersion.

[README](../README.md)
