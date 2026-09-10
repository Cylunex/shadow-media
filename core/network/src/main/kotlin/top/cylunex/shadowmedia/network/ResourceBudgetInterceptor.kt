package top.cylunex.shadowmedia.network

import java.io.IOException
import kotlinx.coroutines.*
import okhttp3.*
import okio.*

/** Hold a budget permit through response consumption, not just response headers. */
class ResourceBudgetInterceptor(private val priority: () -> ResourcePriority) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val permit = try { runBlocking {
            val waiter = async { ResourceScheduler.process.acquire(priority()) }
            val cancellation = launch { while (isActive) { if (chain.call().isCanceled()) { waiter.cancel(); break }; delay(25) } }
            try { waiter.await() } finally { cancellation.cancel() }
        } } catch (e: CancellationException) { throw IOException("请求已取消", e) }
        try {
            val response = chain.proceed(chain.request())
            val original = response.body
            val source = object : ForwardingSource(original.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount).also { if (it == -1L) runBlocking { permit.release() } }
                } catch (e: Exception) { runBlocking { permit.release() }; throw e }
                override fun close() { try { super.close() } finally { runBlocking { permit.release() } } }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source() = source
            }).build()
        } catch (e: Exception) { runBlocking { permit.release() }; throw e }
    }
}
