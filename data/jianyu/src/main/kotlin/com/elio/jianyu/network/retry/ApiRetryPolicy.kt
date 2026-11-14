package com.elio.jianyu.network.retry

import java.math.BigInteger
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ApiRetryPolicy {
    /**
     * 根据当前失败类型和当前 Key 的已尝试次数（重试计数），决定下一步的操作。
     */
    fun getDecision(
        failure: ApiCallFailure,
        sameKeyAttemptCount: Int
    ): ApiRetryDecision {
        return when (failure) {
            is ApiCallFailure.Http -> {
                when (failure.code) {
                    400, 404 -> ApiRetryDecision.STOP_REQUEST
                    401, 403 -> ApiRetryDecision.TRY_NEXT_KEY
                    429 -> ApiRetryDecision.COOLDOWN_AND_TRY_NEXT_KEY
                    408 -> {
                        if (sameKeyAttemptCount < 1) {
                            ApiRetryDecision.RETRY_SAME_KEY
                        } else {
                            ApiRetryDecision.TRY_NEXT_KEY
                        }
                    }
                    in 500..599 -> {
                        if (sameKeyAttemptCount < 2) {
                            ApiRetryDecision.RETRY_SAME_KEY
                        } else {
                            ApiRetryDecision.TRY_NEXT_KEY
                        }
                    }
                    else -> ApiRetryDecision.STOP_REQUEST
                }
            }
            is ApiCallFailure.Network -> {
                if (sameKeyAttemptCount < 1) {
                    ApiRetryDecision.RETRY_SAME_KEY
                } else {
                    ApiRetryDecision.TRY_NEXT_KEY
                }
            }
            is ApiCallFailure.Serialization -> ApiRetryDecision.STOP_REQUEST
            is ApiCallFailure.Unknown -> ApiRetryDecision.STOP_REQUEST
        }
    }

    fun parseRetryAfterMs(headerValue: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val value = headerValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.all { it in '0'..'9' }) {
            return (BigInteger(value) * BigInteger.valueOf(1000L))
                .min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
        }
        // HTTP-date 兼容 IMF-fixdate、RFC 850 和 asctime；每次创建解析器以避免线程不安全。
        for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
                set2DigitYearStart(Calendar.getInstance(timeZone, Locale.US).apply {
                    timeInMillis = nowMs
                    add(Calendar.YEAR, -49)
                }.time)
            }
            val position = ParsePosition(0)
            val date: Date = format.parse(value, position) ?: continue
            if (position.index != value.length) continue
            return (BigInteger.valueOf(date.time) - BigInteger.valueOf(nowMs))
                .max(BigInteger.ZERO).min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
        }
        return null
    }
}

/** 等待时间和到期时间相加时饱和，不能因溢出变成负数并提前重试。 */
internal fun saturatingAddMs(first: Long, second: Long): Long {
    val positive = second.coerceAtLeast(0L)
    return if (first > Long.MAX_VALUE - positive) Long.MAX_VALUE else first + positive
}
