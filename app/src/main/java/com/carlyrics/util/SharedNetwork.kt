package com.carlyrics.util

import com.google.gson.Gson
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared network and serialization singletons so HTTP repositories and plugin runners
 * reuse one OkHttp Dispatcher & ConnectionPool and one Gson instance instead of spawning
 * independent thread pools and reflection caches.
 */
object SharedNetwork {

    val gson: Gson by lazy { Gson() }

    val baseOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
