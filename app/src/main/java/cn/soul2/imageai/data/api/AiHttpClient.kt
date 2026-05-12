package cn.soul2.imageai.data.api

import cn.soul2.imageai.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * HTTP客户端单例
 * 配置OkHttp，包括超时、重试、日志
 */
object AiHttpClient {

    private val apiKey: String = BuildConfig.AI_API_KEY.ifEmpty { "" }
    private val baseUrl: String = BuildConfig.AI_API_URL
    val model: String = BuildConfig.AI_MODEL

    private var _client: OkHttpClient? = null

    val client: OkHttpClient
        get() = _client ?: buildClient().also { _client = it }

    private fun buildClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
        }

        val authInterceptor = Interceptor { chain ->
            val requestBuilder = chain.request().newBuilder()
                .addHeader("Content-Type", "application/json")

            if (apiKey.isNotEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            chain.proceed(requestBuilder.build())
        }

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 获取基础URL
     */
    fun getBaseUrl(): String = baseUrl
}
