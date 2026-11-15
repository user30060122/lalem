package com.example.lalem

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("lalem_updates", 0)
    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private var lastSuccessfulCheck: Long? = null
    private var shownVersion: String? = null

    var checking by mutableStateOf(false)
        private set
    var available by mutableStateOf<PublishedVersion?>(null)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set

    fun check(manual: Boolean = false) {
        if (checking || available != null) return
        val lastCheck = lastSuccessfulCheck
        if (!manual && lastCheck != null && SystemClock.elapsedRealtime() - lastCheck < CHECK_INTERVAL_MS) return

        checking = true
        viewModelScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { fetchVersion() }
                lastSuccessfulCheck = SystemClock.elapsedRealtime()
                val current = PublishedVersion.parse(BuildConfig.VERSION_NAME)
                if (current != null && latest > current) {
                    val ignored = preferences.getStringSet(IGNORED, emptySet()).orEmpty()
                    if (manual || (latest.key !in ignored && latest.key != shownVersion)) {
                        shownVersion = latest.key
                        available = latest
                    }
                } else if (manual) {
                    feedback = "当前已是最新版本"
                }
            } catch (_: IOException) {
                if (manual) feedback = "暂时无法获取更新，请稍后重试"
            } finally {
                checking = false
            }
        }
    }

    fun ignoreVersion() {
        val version = available ?: return
        val ignored = preferences.getStringSet(IGNORED, emptySet()).orEmpty()
        preferences.edit().putStringSet(IGNORED, ignored + version.key).apply()
        closePrompt()
    }

    fun closePrompt() { available = null }

    fun clearFeedback() { feedback = null }

    private fun fetchVersion(): PublishedVersion {
        val request = Request.Builder()
            .url(PgyerInstallPage.URL)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Install page unavailable")
            PgyerInstallPage.parseVersion(response.peekBody(256 * 1024L).string())
                ?: throw IOException("Install page version missing")
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
        const val IGNORED = "ignored_versions"
    }
}

internal object PgyerInstallPage {
    const val URL = "https://www.pgyer.com/laleme-android-t"
    private val versionLabel = Regex(
        """<div\b[^>]*\bclass\s*=\s*["'][^"']*\bapp-info\b[^"']*["'][^>]*>\s*(?:版本|Version)\s*[:：]\s*([0-9]+(?:\.[0-9]+)*)(?=\s*(?:\(|<))""",
        RegexOption.IGNORE_CASE
    )

    fun parseVersion(html: String): PublishedVersion? =
        versionLabel.find(html)?.groupValues?.get(1)?.let(PublishedVersion::parse)
}

internal class PublishedVersion private constructor(val name: String, private val parts: List<Int>) :
    Comparable<PublishedVersion> {
    val key: String get() = parts.joinToString(".")

    override fun compareTo(other: PublishedVersion): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(index) { 0 }.compareTo(other.parts.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    companion object {
        private val numericVersion = Regex("""[0-9]+(?:\.[0-9]+)*""")

        fun parse(name: String): PublishedVersion? {
            if (!numericVersion.matches(name)) return null
            val parts = name.split('.').map { it.toIntOrNull() ?: return null }
            return PublishedVersion(name, parts.dropLastWhile { it == 0 }.ifEmpty { listOf(0) })
        }
    }
}
