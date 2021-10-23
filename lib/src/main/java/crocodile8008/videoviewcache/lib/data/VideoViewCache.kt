package crocodile8008.videoviewcache.lib.data

import android.content.Context
import crocodile8008.videoviewcache.lib.closeSilent
import crocodile8008.videoviewcache.lib.log
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.Exception
import kotlin.math.abs

internal object VideoViewCache {

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun loadInFileCached(
        url: String,
        headers: Map<String, String>?,
        context: Context,
    ): Single<String> = Single
        .create { emitter: SingleEmitter<String> ->

            log("request: $url")
            var file: File? = null
            var fileTmp: File? = null
            var sink: BufferedSink? = null
            var body: ResponseBody? = null

            try {
                file = getOutputFile(url, context)
                if (file.exists() && file.length() > 0) {
                    log("cached: $url")
                    if (!emitter.isDisposed) {
                        emitter.onSuccess(file.absolutePath)
                        return@create
                    }
                }

                val request: Request = Request.Builder()
                    .url(url)
                    .also { builder ->
                        headers?.entries?.forEach { (name, value) ->
                            builder.addHeader(name, value)
                        }
                    }
                    .build()

                val response: Response = okHttpClient.newCall(request).execute()

                file.tryDelete()
                fileTmp = getOutputFile(url, context, isTmp = true)
                sink = fileTmp.sink().buffer()
                body = response.body!!
                sink.writeAll(body.source())
                fileTmp.renameTo(file)

                log("ready: $url")
                if (!emitter.isDisposed) {
                    emitter.onSuccess(file.absolutePath)
                }
            } catch (e: Exception) {
                val interrupted = Thread.interrupted()
                file?.tryDelete()
                log("request exception: $e" + ", was interrupted: $interrupted, url: $url")
                if (!emitter.isDisposed) {
                    emitter.tryOnError(e)
                }
            } finally {
                sink.closeSilent()
                body.closeSilent()
                fileTmp?.tryDelete()
            }
        }
        .retry(1)
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())

    internal fun tryDeleteFile(file: File): Boolean {
        try {
            if (file.exists()) {
                val deleted = file.delete()
                log("delete file: $deleted")
                return deleted
            }
        } catch (e: Exception) {
            log("try delete file: $e")
        }
        return false
    }

    internal fun File.tryDelete() {
        tryDeleteFile(this)
    }

    @Throws(IOException::class)
    internal fun getOutputFile(url: String, context: Context, isTmp: Boolean = false): File {
        val outputFileDir = getOutputDirPath(context)
        val fileDir = File(outputFileDir)
        if (!fileDir.exists()) {
            fileDir.mkdirs()
        }
        var outputFileName = abs(url.hashCode()).toString() + url.split("/").last()
        if (isTmp) {
            outputFileName = "tmp_$outputFileName"
        }
        return File(outputFileDir + outputFileName)
    }

    internal fun getOutputDirPath(context: Context): String =
        context.cacheDir.absolutePath + "/video_view_cache/"
}