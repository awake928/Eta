package io.github.mangi.eta.data.repository.catalog

import android.content.Context
import io.github.mangi.eta.data.model.ProviderSourceTypes
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 从随 APK 打包的 models.dev 目录里读取模型价目（单位 USD / 1M tokens）。
 *
 * 只服务统计面板上的花费估算：解析失败一律返回 null，任何情况都不向 UI 抛异常。
 * 价目表体积约 400 KB，解析一次后常驻内存。
 */
internal object ModelPricing {
    private const val PRICING_ASSET = "catalog/models-dev-api.json.gzip"
    private const val BUILTIN_MODEL_PREFIX = "builtin-"
    private const val DEFAULT_PRICING_GROUP = ProviderSourceTypes.DEEPSEEK

    data class ModelCost(
        val input: Double,
        val output: Double,
        val cacheRead: Double,
        val cacheWrite: Double?,
        val reasoning: Double?,
    )

    @Volatile
    private var pricingCache: Map<String, Map<String, ModelCost>>? = null

    /**
     * 查询单个模型的价目。
     *
     * Eta 内置模型 id 形如 `builtin-deepseek-flash`，目录里的 key 是 `deepseek-flash`，
     * 所以先去掉 `builtin-` 前缀；查找顺序为「当前源类型分组 → deepseek 分组 → 全局同名 key」。
     */
    suspend fun costFor(context: Context, providerSourceType: String, modelId: String): ModelCost? {
        val modelKey = modelId.trim().removePrefix(BUILTIN_MODEL_PREFIX)
        if (modelKey.isEmpty()) return null
        val table = pricing(context.applicationContext) ?: return null
        table[providerSourceType]?.get(modelKey)?.let { return it }
        table[DEFAULT_PRICING_GROUP]?.get(modelKey)?.let { return it }
        // 中转站的源类型不是 deepseek 时按目录 key 全局兜底；按键名排序取第一个命中，避免结果随机。
        return table.keys
            .sorted()
            .asSequence()
            .mapNotNull { group -> table[group]?.get(modelKey) }
            .firstOrNull()
    }

    private suspend fun pricing(context: Context): Map<String, Map<String, ModelCost>>? {
        pricingCache?.let { return it }
        return withContext(Dispatchers.IO) {
            pricingCache?.let { return@withContext it }
            val parsed = try {
                parse(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withContext null
            }
            pricingCache = parsed
            parsed
        }
    }

    private fun parse(context: Context): Map<String, Map<String, ModelCost>> {
        val raw = context.assets.open(PRICING_ASSET).use { stream ->
            GZIPInputStream(stream).use { gzip -> gzip.readBytes().toString(Charsets.UTF_8) }
        }
        val root = JSONObject(raw)
        val result = HashMap<String, Map<String, ModelCost>>()
        val providerKeys = root.keys()
        while (providerKeys.hasNext()) {
            val providerKey = providerKeys.next()
            val models = root.optJSONObject(providerKey)?.optJSONObject("models") ?: continue
            val costs = HashMap<String, ModelCost>()
            val modelKeys = models.keys()
            while (modelKeys.hasNext()) {
                val modelKey = modelKeys.next()
                parseCost(models.optJSONObject(modelKey)?.optJSONObject("cost"))?.let { costs[modelKey] = it }
            }
            if (costs.isNotEmpty()) result[providerKey] = costs
        }
        return result
    }

    private fun parseCost(json: JSONObject?): ModelCost? {
        json ?: return null
        val input = json.optionalPrice("input") ?: return null
        val output = json.optionalPrice("output") ?: return null
        return ModelCost(
            input = input,
            output = output,
            // 目录里个别模型没有 cache_read，退化成按输入价计。
            cacheRead = json.optionalPrice("cache_read") ?: input,
            cacheWrite = json.optionalPrice("cache_write"),
            reasoning = json.optionalPrice("reasoning"),
        )
    }

    private fun JSONObject.optionalPrice(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() && it >= 0.0 }
    }
}
