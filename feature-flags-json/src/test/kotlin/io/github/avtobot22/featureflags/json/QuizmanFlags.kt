package io.github.avtobot22.featureflags.json

import io.github.avtobot22.featureflags.Feature
import io.github.avtobot22.featureflags.ParametersDecoder

internal object QuizmanFlags {
    val guestMode = Feature.boolean("dashboard.guestMode")
    val parallax = Feature.boolean("dashboard.parallax")
    val hero = Feature.boolean("dashboard.hero")
    val forceRefreshButton = Feature.boolean("dashboard.forceRefreshButton")

    val prefetch = Feature.config(
        key = "dashboard.prefetch",
        defaultParams = PrefetchParams(depth = 2, maxConcurrent = 8),
        decoder = ParametersDecoder { parameters, defaults ->
            PrefetchParams(
                depth = parameters.int("depth", default = defaults.depth, min = 0),
                maxConcurrent = parameters.int("maxConcurrent", default = defaults.maxConcurrent, min = 1),
            )
        },
    )

    val pagination = Feature.config(
        key = "dashboard.pagination",
        defaultParams = PaginationParams(pageSize = 20),
        decoder = ParametersDecoder { parameters, defaults ->
            PaginationParams(pageSize = parameters.int("pageSize", default = defaults.pageSize, min = 1))
        },
    )

    data class PrefetchParams(val depth: Int, val maxConcurrent: Int)

    data class PaginationParams(val pageSize: Int)
}
