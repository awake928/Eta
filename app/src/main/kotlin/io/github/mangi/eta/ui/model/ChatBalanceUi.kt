package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * 统计卡片顶部的账户余额与本会话花费显示态。
 *
 * 这里只承载"要不要显示、显示成什么字符串"：余额的取数（中转站余额接口 / 本地记账）
 * 与花费的算法（模型单价 × 会话 Token）都由调用方完成后再塞进来，
 * 因此不接数据时把 [ChatBalanceUi] 传 null，统计卡片与上游行为完全一致。
 */
@Immutable
public data class ChatBalanceUi(
    /** 已格式化好的余额，例如 `¥78.40`；为 null 时整块余额 Section 不渲染。 */
    val availableText: String? = null,
    /** 已格式化好的本会话花费，例如 `¥0.23`；为 null 时该行不渲染。 */
    val sessionCostText: String? = null,
) {
    public val hasBalance: Boolean get() = !availableText.isNullOrBlank()

    public val hasSessionCost: Boolean get() = !sessionCostText.isNullOrBlank()

    public val isEmpty: Boolean get() = !hasBalance && !hasSessionCost
}

/** 把金额按币种符号格式化成两位小数，供余额与花费展示复用。 */
public object ChatMoneyFormat {
    public fun amount(
        value: Double,
        currencyCode: String = "CNY",
        locale: Locale = Locale.getDefault(),
    ): String {
        val numberFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val symbol = runCatching { Currency.getInstance(currencyCode.uppercase()) }
            .map { it.getSymbol(locale) }
            .getOrElse { currencyCode.uppercase() }
        return "$symbol${numberFormat.format(value)}"
    }
}
