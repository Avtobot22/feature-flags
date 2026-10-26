# Определения флагов и параметры

Определение связывает серверный ключ с типом результата и defaults приложения. Его удобно хранить рядом с кодом фичи и переиспользовать для `get()` и `observe()`.

## Boolean

```kotlin
import io.github.avtobot22.featureflags.Feature

val guestMode = Feature.boolean("dashboard.guestMode", default = false)
```

Результат чтения - `Boolean`. Пока конфигурация не загружена или ключ отсутствует, возвращается `false` из определения. Для присутствующего флага используется серверный `enabled`, с учётом [процентного правила](percentage-rollout.md), если оно задано. Ключи чувствительны к регистру: `dashboard.guestMode` и `dashboard.GuestMode` различаются.

## Флаг с настройками

`Feature.config` возвращает `FeatureValue<P>` с полями `enabled` и `params`. Defaults параметров задаются одним объектом и передаются decoder при каждом чтении присутствующего флага.

```kotlin
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.ParametersDecoder

data class FeedParams(
    val pageSize: Int,
    val title: String,
    val showImages: Boolean,
)

val feedDefaults = FeedParams(pageSize = 20, title = "Новости", showImages = true)

val feed = Feature.config(
    key = "feed.redesign",
    defaultEnabled = false,
    defaultParams = feedDefaults,
    decoder = ParametersDecoder { reader, defaults ->
        FeedParams(
            pageSize = reader.int("pageSize", default = defaults.pageSize, min = 1, max = 100),
            title = reader.string("title", default = defaults.title),
            showImages = reader.boolean("showImages", default = defaults.showImages),
        )
    },
)
```

Соответствующий фрагмент серверной конфигурации:

```json
{
  "featureToggles": [
    {
      "feature": "feed.redesign",
      "enabled": false,
      "parameters": {"pageSize": 30, "title": "Лента", "showImages": false}
    }
  ]
}
```

При чтении этого флага `enabled` будет `false`, а `params.pageSize` - `30`. Выключение не удаляет настройки. Приложение само проверяет `enabled` перед использованием функции.

| Состояние флага | Результат `Feature.config` |
|---|---|
| Ключ отсутствует или система ещё не инициализирована | `defaultEnabled` и тот же `defaultParams`; decoder не вызывается |
| Ключ присутствует, `parameters` отсутствует или равен `null` | Decoder получает reader без значений и defaults |
| Ключ присутствует, `enabled=false` | Decoder вызывается; параметры доступны |
| Ключ присутствует, но процентное правило исключило пользователя | `enabled=false`; параметры доступны |

Defaults и результаты decoder должны быть неизменяемыми. SDK разделяет default-объект между чтениями, а Flow сравнивает результаты через `equals`. Decoder не должен менять defaults, выполнять I/O или зависеть от изменяемого внешнего состояния. Исключение decoder распространяется из `get()` или завершает соответствующий collector `observe()`; автоматической подмены результата defaults нет.

## Правила чтения параметров

`ParameterReader` предоставляет три метода:

| Метод | Принимает | При отсутствии, `null` или неверном типе |
|---|---|---|
| `int(name, default, min, max)` | Целочисленный JSON literal в диапазоне `Int` | Возвращает `default` без изменения |
| `boolean(name, default)` | JSON `true` или `false` | Возвращает `default` |
| `string(name, default)` | JSON-строку | Возвращает `default` |

Для `int` строки `"30"`, числа `30.0`, `3e1`, переполнение и объекты не преобразуются в число. Корректное целое ограничивается включительными `min`/`max`; например, `200` при `max=100` становится `100`. Default не ограничивается этими границами. При `min > max` метод бросает `IllegalArgumentException`.

Вложенные структуры и другие типы недоступны через текущий `ParameterReader`.

Для собственных правил оценки можно реализовать `FlagDefinition<T>`; его `evaluate(EvaluationInput)` должен быть детерминированным и работать без I/O. `present` отличает отсутствующий флаг от присутствующего выключенного. Это расширение правил оценки не добавляет методов чтения вложенных параметров.

## Отдельный объект параметров

Если полный документ не нужен, `JsonParameters.decode` возвращает `ParameterReader` из JSON object или `null`:

```kotlin
import io.github.avtobot22.featureflags.json.JsonParameters

val parameters = JsonParameters.decode("""{"pageSize":200}""")
val pageSize = parameters.int("pageSize", default = 20, min = 1, max = 100)
check(pageSize == 100)
```

Неверный синтаксис, массив, строка или скаляр вместо object/null дают `IllegalArgumentException`. Это чтение не создаёт систему и не публикует конфигурацию. Правила полного ответа описаны в [формате JSON](json-format.md).

[README](../README.md)
