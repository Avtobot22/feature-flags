# Процентная раскатка

Процентное правило включает присутствующий серверный флаг для стабильной части аудитории. Оно оценивается на клиенте по ключу, который передаёт приложение. SDK не собирает идентификаторы и не назначает пользователя в группу случайным образом при каждом чтении.

## Документ v2

Используй `JsonRolloutConfigurationCodec` и согласованный [формат v2](json-format.md):

```json
{
  "schemaVersion": 2,
  "flags": [
    {
      "feature": "dashboard.guestMode",
      "enabled": true,
      "rollout": {"kind": "percentage", "basisPoints": 2500, "group": "guest-v1"}
    }
  ]
}
```

`basisPoints=2500` означает 25%; диапазон - 0..10000 с шагом 0.01%. `group` задаёт стабильное распределение. Отсутствие `rollout` оставляет обычное поведение `enabled`, без требования ключа пользователя.

## Reader с контекстом

```kotlin
import io.github.avtobot22.featureflags.EvaluationContext
import io.github.avtobot22.featureflags.FallbackPolicy
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.FeatureFlagsReader
import io.github.avtobot22.featureflags.FeatureFlagsSource
import io.github.avtobot22.featureflags.FeatureFlagsSystem
import io.github.avtobot22.featureflags.json.JsonRolloutConfigurationCodec

val guestMode = Feature.boolean("dashboard.guestMode", default = false)

fun createRolloutSystem(remote: FeatureFlagsSource): FeatureFlagsSystem = FeatureFlagsSystem(
    remote = remote,
    codec = JsonRolloutConfigurationCodec,
    fallback = FallbackPolicy.Never,
)

fun readerForSession(system: FeatureFlagsSystem, stableKey: String): FeatureFlagsReader =
    system.reader.withContext(EvaluationContext(targetingKey = stableKey))
```

Владелец вызывает `system.manager.refresh()`, а фичам передаёт результат `readerForSession`. Чтение и Flow этого reader используют общий активный снимок системы и переданный контекст без I/O.

Выбирай стабильный непрозрачный ключ пользователя или установки. Не передавай access token; не создавай новый случайный ключ при каждом запуске, если нужно сохранить аудиторию. Ключ чувствителен к регистру и Unicode, нормализации нет. `null` означает отсутствие ключа, пустая строка и некорректные UTF-16 surrogate sequences отклоняются.

| Состояние | Результат |
|---|---|
| Серверный ключ отсутствует | Default определения |
| `enabled=false` | Выключено независимо от правила |
| `enabled=true`, правила нет | Включено без контекста |
| `enabled=true`, правило есть, ключа нет | Выключено даже при 100% |
| Правило 0%, ключ передан | Выключено |
| Правило 100%, ключ передан | Включено |
| Правило между 0% и 100%, ключ передан | Включено при попадании в стабильную группу |

Параметры присутствующего флага сохраняются и декодируются, даже если правило выключило его для этого пользователя.

`withContext()` создаёт reader, навсегда привязанный к данному контексту. При смене сессии создай новый reader и отмени старые подписки. Старый reader и его collectors продолжат оценивать флаги для прежнего ключа. Исходный `system.reader` остаётся без контекста. Пользовательская реализация `FeatureFlagsReader` должна отдельно реализовать `withContext`; стандартный метод интерфейса бросает `UnsupportedOperationException`.

При прямом разборе документа тот же контекст передаётся в `configuration.evaluate(definition, EvaluationContext(key))`.

## Стабильность распределения

Одинаковые `group` и ключ дают одинаковый bucket после перезапуска и в разных потоках. Ключ флага не входит в hash: несколько флагов с одной группой намеренно используют общее распределение. Разные проценты в одной группе образуют вложенные аудитории. Новый `group` меняет распределение.

При неизменных группе и ключе рост процента сохраняет прежних участников. Доля на конечной аудитории приблизительна: правило не гарантирует ровно 25 пользователей из 100.

Алгоритм `percentage-v1` закреплён так:

1. В SHA-256 подаётся ASCII `feature-flags/percentage-v1`.
2. Для `group`, затем для ключа подаётся длина UTF-8 в 4 байтах big endian и сами UTF-8 байты.
3. Первые 4 байта digest читаются как unsigned big-endian 32-bit integer.
4. `bucket = integer % 10000`; правило включает флаг при `bucket < basisPoints`.

Пара `guest-v1` / `user-1` даёт bucket `2462`, поэтому попадает в правило 2500 basis points. В Core правило также доступно напрямую через `PercentageRollout(basisPoints, group).includes(context)`.

## Кэш и выключение

Кэш хранит общий документ правил, а каждый reader оценивает его для своего контекста. Для такого документа смена reader не требует новой загрузки. Если сервер персонализирует сам ответ, `withContext()` не изолирует эти данные: нужны отдельная область кэша, invalidation старой системы и новая система для следующей сессии. Endpoint и версия codec должны входить в scope.

Серверное `enabled=false` действует после получения нового документа. `CACHE_FIRST` и offline могут сохранять прежнее включение. Для критичных флагов выбери `REMOTE_ONLY` без restore либо `maxAgeMillis=ttlMillis` для запрета stale и отдельно ограничь в приложении срок чтения уже активного снимка. TTL сам по себе не снимает снимок: [поведение кэша](cache.md).

Флаги управляют клиентским поведением; доступ к защищённым операциям проверяет серверная авторизация.

[README](../README.md)
