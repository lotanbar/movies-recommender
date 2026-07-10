package com.moviesrecommender.data.local

data class UsageAverage(
    val mode: String,
    val count: Int,
    val avgTotalTokens: Double,
    val avgCostUsd: Double,
    val avgDurationMs: Double
)

class UsageStatsService(private val dao: UsageDao) {
    suspend fun record(
        mode: String,
        inputTokens: Long,
        outputTokens: Long,
        cacheWriteTokens: Long,
        cacheReadTokens: Long,
        costUsd: Double,
        durationMs: Long
    ) = dao.insert(
        UsageEntity(
            mode = mode,
            timestamp = System.currentTimeMillis(),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheWriteTokens = cacheWriteTokens,
            cacheReadTokens = cacheReadTokens,
            costUsd = costUsd,
            durationMs = durationMs
        )
    )

    suspend fun getAll(): List<UsageEntity> = dao.getAll()

    suspend fun getAverages(): List<UsageAverage> =
        dao.getAll().groupBy { it.mode }.map { (mode, entries) ->
            UsageAverage(
                mode = mode,
                count = entries.size,
                avgTotalTokens = entries.map { it.inputTokens + it.outputTokens + it.cacheWriteTokens + it.cacheReadTokens }.average(),
                avgCostUsd = entries.map { it.costUsd }.average(),
                avgDurationMs = entries.map { it.durationMs }.average()
            )
        }
}
