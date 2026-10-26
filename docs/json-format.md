# Форматы JSON

Приложение явно выбирает один codec для remote, local и кэша. SDK поддерживает исходный формат `featureToggles` и отдельный формат v2 с процентными правилами. Автоматического определения версии нет.

## Исходный формат

`JsonConfigurationCodec` принимает объект с обязательным массивом `featureToggles`:

```json
{
  "featureToggles": [
    {"feature": "dashboard.guestMode", "enabled": true},
    {
      "feature": "feed.redesign",
      "enabled": false,
      "parameters": {"pageSize": 30, "title": "Лента", "showImages": true}
    }
  ]
}
```

| Поле | Контракт |
|---|---|
| `featureToggles` | Обязательный массив объектов; пустой массив допустим |
| `feature` | Обязательная строка; ключ сравнивается с учётом регистра, без нормализации |
| `enabled` | Обязательный JSON Boolean, без преобразования из строки или числа |
| `parameters` | Необязательный объект; отсутствие и `null` дают reader без значений |

Имена флагов, ещё неизвестные приложению, сохраняются в конфигурации. Дополнительные поля объекта ответа и флагов игнорируются, кроме зарезервированных полей версии и правил: на корне запрещены `schemaVersion`, `flags`, `rollout`, `rules`, в объекте флага - `rollout`, `rules`.

Ключ `feature` не обязан совпадать с заранее объявленным определением. Codec не проверяет каталог флагов приложения и не запускает parameter decoder при загрузке. Параметры разбираются при оценке соответствующего определения; правила для неверных типов описаны в [руководстве по параметрам](flags-and-parameters.md).

## Формат v2

`JsonRolloutConfigurationCodec` принимает только `schemaVersion=2` и массив `flags`:

```json
{
  "schemaVersion": 2,
  "flags": [
    {
      "feature": "dashboard.guestMode",
      "enabled": true,
      "rollout": {"kind": "percentage", "basisPoints": 2500, "group": "guest-v1"}
    },
    {"feature": "feed.redesign", "enabled": false, "parameters": {"pageSize": 30}}
  ]
}
```

`feature`, `enabled` и `parameters` имеют те же типы. Объект `rollout` необязателен, но не может быть `null`. Если он задан, обязательны ровно три поля:

| Поле | Допустимое значение |
|---|---|
| `kind` | Строка `"percentage"` |
| `basisPoints` | Целочисленный JSON literal от 0 до 10000 включительно |
| `group` | Непустая строка корректного Unicode |

`2500` означает 25%, минимальный шаг - 0.01%. Строка `"2500"`, дробь `2500.0` и экспонента `25e2` отклоняются. `schemaVersion` также требует целочисленный literal `2`.

Неизвестные поля на корне, в объекте флага и правила отклоняются. Схема `parameters` остаётся открытой: новые параметры не требуют изменения codec. Правила проверяются даже у выключенных флагов и ключей, неизвестных приложению.

Согласуй отдельный endpoint или версию запроса для v2. Не добавляй `rollout` в ответ старого endpoint и не смешивай `featureToggles` с `flags`: исходный codec отклоняет v2, а v2 codec отклоняет исходный формат. При смене формата измени и [область кэша](cache.md). Оценка процента и контекст читателя описаны в [процентной раскатке](percentage-rollout.md).

## Что отклоняет весь документ

Оба codec бросают `IllegalArgumentException` при неверном синтаксисе, неправильном типе обязательного поля, недопустимом `parameters` и повторении серверного ключа `feature`. Дубликаты имён полей на корне, в объекте флага или правила тоже запрещены, включая одинаковые имена с разными JSON escape-последовательностями.

Внутри `parameters` повторяющиеся имена используют последнее значение. При этом весь исходный JSON проходит синтаксическую проверку: неверный literal нельзя скрыть более поздним дубликатом. Не используй повторения для формирования ответа.

Пустая строка тела, HTTP 204 и валидный JSON с пустым массивом - разные случаи. Отсутствие тела отклоняет HTTP-адаптер; пустую строку отвергает codec; `{"featureToggles":[]}` или `{"schemaVersion":2,"flags":[]}` - успешная пустая конфигурация, к которой применяются [политики загрузки](loading-and-observation.md).

## Разбор без системы

```kotlin
import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.json.JsonConfigurationCodec

val guestMode = Feature.boolean("dashboard.guestMode", default = false)
val configuration = JsonConfigurationCodec.decode(
    """{"featureToggles":[{"feature":"dashboard.guestMode","enabled":true}]}""",
)

check(configuration.flagCount == 1)
check(!configuration.isEmpty)
check(configuration.evaluate(guestMode))
```

Полученная `Configuration` неизменяема. Разбор не делает I/O и не меняет активный снимок какой-либо системы. Для v2 с контекстом используется `configuration.evaluate(definition, context)`.

[README](../README.md)
