package com.elio.jianyu.privacy

fun interface JianyuDataMaintenance {
    /** 调用方完成用户确认后执行，仅清理见域拥有的数据。 */
    suspend fun clearAll(): String
}
