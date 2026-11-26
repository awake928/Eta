package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.provider.AgentHttpClient
import io.github.mangi.eta.data.model.ProviderSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * DeepSeek 官方账户余额查询（`GET {baseUrl}/user/balance`）。
 *
 * 只服务统计面板顶部一行展示：任何失败都折算成 [BalanceState.Failed]，绝不向 UI 抛异常。
 * 成功结果在内存里缓存 60 秒，避免每次弹出面板都打网络。
 */
internal object DeepSeekBalanceRepository {
    private const val BALANCE_PATH = "/user/balance"
    private const val CACHE_TTL_MS = 60_000L
    private const val CURRENCY_CNY = "CNY"

    sealed interface BalanceState {
        data object Idle : BalanceState
        data object Loading : BalanceState
        data class Loaded(val total: Double, val currency: String) : BalanceState
        data object Failed : BalanceState
    }

    private data class CachedBalance(
        val providerId: String,
        val apiKey: String,
        val state: BalanceState.Loaded,
        val fetchedAt: Long,
    )

    @Volatile
    private var cache: CachedBalance? = null

    /**
     * 读取余额。[force] 为 false 且缓存未过期时直接返回缓存，不发起请求。
     */
    suspend fun fetch(provider: ProviderSetting, force: Boolean): BalanceState {
        val baseUrl = provider.baseUrl.trim().trimEnd('/')
        val apiKey = provider.apiKey.trim()
        if (baseUrl.isEmpty() || apiKey.isEmpty()) return BalanceState.Failed
        val cached = cache
        if (!force && cached != null &&
            cached.providerId == provider.id &&
            cached.apiKey == apiKey &&
            System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS
        ) {
            return cached.state
        }
        return withContext(Dispatchers.IO) {
            query(
                baseUrl = baseUrl,
                apiKey = apiKey,
                providerId = provider.id,
                fetchedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun query(baseUrl: String, apiKey: String, providerId: String, fetchedAt: Long): BalanceState = try {
        // api_key 模式下 AuthorizedProviderHttp 不会补 Authorization 头，必须自己带。
        val request = Request.Builder()
            .url(baseUrl + BALANCE_PATH)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        AgentHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return BalanceState.Failed
            val loaded = parse(response.body.string()) ?: return BalanceState.Failed
            cache = CachedBalance(
                providerId = providerId,
                apiKey = apiKey,
                state = loaded,
                fetchedAt = fetchedAt,
            )
            loaded
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        BalanceState.Failed
    }

    private fun parse(body: String): BalanceState.Loaded? = try {
        val infos = JSONObject(body).optJSONArray("balance_infos")
        if (infos == null || infos.length() == 0) {
            null
        } else {
            val entries = (0 until infos.length()).mapNotNull { index ->
                infos.optJSONObject(index)?.let { info -> parseInfo(info) }
            }
            // 优先人民币，拿不到就取第一条；只展示 total_balance。
            entries.firstOrNull { it.currency == CURRENCY_CNY } ?: entries.firstOrNull()
        }
    } catch (_: Exception) {
        null
    }

    private fun parseInfo(json: JSONObject): BalanceState.Loaded? {
        // 金额在响应里是字符串。
        val total = json.optString("total_balance").trim().toDoubleOrNull() ?: return null
        if (!total.isFinite() || total < 0.0) return null
        val currency = json.optString("currency").trim().ifEmpty { CURRENCY_CNY }
        return BalanceState.Loaded(total = total, currency = currency)
    }
}
