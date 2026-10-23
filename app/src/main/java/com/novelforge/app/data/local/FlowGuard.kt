package com.novelforge.app.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * 观察流一旦抛异常，stateIn 会把异常交给 viewModelScope，主线程直接闪退。
 * 这里把查询/映射失败收成降级值，让界面保持可打开。
 */
fun <T> Flow<T>.orFallback(fallback: T): Flow<T> = catch { emit(fallback) }
