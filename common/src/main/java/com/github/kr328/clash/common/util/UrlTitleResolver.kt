package com.github.kr328.clash.common.util

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object UrlTitleResolver {

    /**
     * Parse clash://install-config?url=...&name=... or clashmeta://install-config?url=...&name=...
     */
    fun parseInstallConfig(raw: String): Pair<String, String?>? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("clash://install-config", ignoreCase = true) ||
            trimmed.startsWith("clashmeta://install-config", ignoreCase = true)
        ) {
            return try {
                val uri = Uri.parse(trimmed)
                val targetUrl = uri.getQueryParameter("url") ?: return null
                val targetName = uri.getQueryParameter("name")?.let { decodeString(it).trim() }?.ifBlank { null }
                targetUrl to targetName
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /**
     * Extracts title from the URL string itself:
     * 1. Fragment (#name)
     * 2. Query parameter (name=... or title=...)
     * 3. Last path segment filename (e.g. /my-config.yaml -> my-config)
     */
    fun extractTitleFromUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null

        val parsedInstall = parseInstallConfig(trimmed)
        val urlToInspect = parsedInstall?.first ?: trimmed
        if (parsedInstall?.second != null) {
            return parsedInstall.second
        }

        return try {
            val uri = Uri.parse(urlToInspect)

            // 1. Fragment (#name)
            val fragment = uri.fragment
            if (!fragment.isNullOrBlank()) {
                val decoded = decodeString(fragment).trim()
                if (decoded.isNotBlank()) {
                    return decoded
                }
            }

            // 2. Query parameter (name=... or title=...)
            val nameParam = uri.getQueryParameter("name") ?: uri.getQueryParameter("title")
            if (!nameParam.isNullOrBlank()) {
                val decoded = decodeString(nameParam).trim()
                if (decoded.isNotBlank()) {
                    return decoded
                }
            }

            // 3. Path segment (e.g. /config/my-server.yaml -> my-server)
            val lastSegment = uri.lastPathSegment
            if (!lastSegment.isNullOrBlank()) {
                val decoded = decodeString(lastSegment).trim()
                val dotIndex = decoded.lastIndexOf('.')
                val cleanedName = if (dotIndex > 0) decoded.substring(0, dotIndex).trim() else decoded
                val ignoredNames = setOf("config", "subscribe", "sub", "clash", "meta", "get", "api", "index", "default")
                if (cleanedName.isNotBlank() && cleanedName.lowercase() !in ignoredNames) {
                    return cleanedName
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches title from HTTP response headers (profile-title, content-disposition):
     */
    suspend fun fetchTitleFromUrl(rawUrl: String, timeoutMs: Int = 5000): String? {
        val trimmed = rawUrl.trim()
        val parsedInstall = parseInstallConfig(trimmed)
        val targetUrl = parsedInstall?.first ?: trimmed

        if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
            return extractTitleFromUrl(rawUrl)
        }

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ClashMetaForAndroid")
                }
                conn.connect()

                // 1. Check profile-title header
                val profileTitle = conn.getHeaderField("profile-title")
                if (!profileTitle.isNullOrBlank()) {
                    val parsed = parseProfileTitle(profileTitle)
                    if (!parsed.isNullOrBlank()) {
                        return@withContext parsed.trim()
                    }
                }

                // 2. Check content-disposition header
                val contentDisposition = conn.getHeaderField("content-disposition")
                if (!contentDisposition.isNullOrBlank()) {
                    val parsed = parseContentDisposition(contentDisposition)
                    if (!parsed.isNullOrBlank()) {
                        return@withContext parsed.trim()
                    }
                }

                // Fallback to URL-based extraction
                extractTitleFromUrl(rawUrl)
            } catch (_: Exception) {
                extractTitleFromUrl(rawUrl)
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun parseProfileTitle(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Check if base64 encoded: base64:xxx
        if (trimmed.startsWith("base64:", ignoreCase = true)) {
            val base64Str = trimmed.substring(7).trim()
            return try {
                val bytes = Base64.decode(base64Str, Base64.DEFAULT)
                String(bytes, StandardCharsets.UTF_8).trim().ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }

        // URL encoded or plain text
        val decoded = decodeString(trimmed).trim()
        return decoded.ifBlank { null }
    }

    fun parseContentDisposition(header: String): String? {
        return try {
            // filename*=UTF-8''...
            val utf8Matcher = Regex("""filename\*\s*=\s*UTF-8''([^;\r\n]+)""", RegexOption.IGNORE_CASE)
            val utf8Match = utf8Matcher.find(header)
            if (utf8Match != null) {
                val rawName = utf8Match.groupValues[1].trim(' ', '"', '\'')
                val decoded = decodeString(rawName)
                return stripExtension(decoded).ifBlank { null }
            }

            // filename="foo.yaml" or filename=foo.yaml
            val stdMatcher = Regex("""filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE)
            val stdMatch = stdMatcher.find(header)
            if (stdMatch != null) {
                val rawName = (stdMatch.groups[1]?.value ?: stdMatch.groups[2]?.value)?.trim(' ', '"', '\'') ?: ""
                val decoded = decodeString(rawName)
                return stripExtension(decoded).ifBlank { null }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun stripExtension(name: String): String {
        val trimmed = name.trim()
        val dotIndex = trimmed.lastIndexOf('.')
        return if (dotIndex > 0) trimmed.substring(0, dotIndex).trim() else trimmed
    }

    private fun decodeString(str: String): String {
        return try {
            URLDecoder.decode(str, "UTF-8")
        } catch (_: Exception) {
            str
        }
    }
}
