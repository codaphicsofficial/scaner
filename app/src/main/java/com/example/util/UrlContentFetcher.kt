package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object UrlContentFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class ExtractedPageInfo(
        val url: String,
        val title: String?,
        val forms: List<String>,
        val scripts: List<String>,
        val isHttps: Boolean,
        val rawHtmlSnippet: String?
    )

    suspend fun fetchPageInfo(targetUrl: String): ExtractedPageInfo = withContext(Dispatchers.IO) {
        val sanitizedUrl = sanitizeUrl(targetUrl)
        val isHttps = sanitizedUrl.startsWith("https://", ignoreCase = true)
        
        try {
            val request = Request.Builder()
                .url(sanitizedUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; ShieldScan) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36 SecureScanner/1.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ExtractedPageInfo(
                        url = sanitizedUrl,
                        title = null,
                        forms = emptyList(),
                        scripts = emptyList(),
                        isHttps = isHttps,
                        rawHtmlSnippet = null
                    )
                }

                val body = response.body
                if (body == null) {
                    return@withContext ExtractedPageInfo(
                        url = sanitizedUrl,
                        title = null,
                        forms = emptyList(),
                        scripts = emptyList(),
                        isHttps = isHttps,
                        rawHtmlSnippet = null
                    )
                }

                // Read at most 64KB to avoid memory issues and keep analysis payload compact
                val maxChars = 65536
                val buffer = CharArray(maxChars)
                val reader = body.charStream()
                val bytesRead = reader.read(buffer, 0, maxChars)
                
                val htmlContent = if (bytesRead > 0) String(buffer, 0, bytesRead) else ""

                // Extract fields using Regex
                val title = extractTitle(htmlContent)
                val forms = extractForms(htmlContent)
                val scripts = extractScripts(htmlContent)

                // Take a small snippet of html (max 2000 chars) for direct view analysis
                val rawHtmlSnippet = if (htmlContent.length > 2000) {
                    htmlContent.substring(0, 2000) + "... [truncated]"
                } else {
                    htmlContent
                }

                ExtractedPageInfo(
                    url = sanitizedUrl,
                    title = title,
                    forms = forms,
                    scripts = scripts,
                    isHttps = isHttps,
                    rawHtmlSnippet = rawHtmlSnippet
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Return empty representation if connection/fetching fails
            ExtractedPageInfo(
                url = sanitizedUrl,
                title = null,
                forms = emptyList(),
                scripts = emptyList(),
                isHttps = isHttps,
                rawHtmlSnippet = null
            )
        }
    }

    private fun sanitizeUrl(url: String): String {
        var trimmed = url.trim()
        if (trimmed.isBlank()) return trimmed
        
        // If it starts with neither http:// nor https://, default to https://
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        return trimmed
    }

    private fun extractTitle(html: String): String? {
        val regex = "<title>(.*?)</title>".toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractForms(html: String): List<String> {
        val formRegex = "<form\\s+(.*?)>".toRegex(RegexOption.IGNORE_CASE)
        val forms = mutableListOf<String>()
        formRegex.findAll(html).take(5).forEach { match ->
            forms.add(match.value)
        }

        // Also check if they ask for sensitive keywords
        val passwordInputRegex = "<input[^>]*type=[\"']password[\"'][^>]*>".toRegex(RegexOption.IGNORE_CASE)
        if (passwordInputRegex.containsMatchIn(html)) {
            forms.add("[Heuristic Warning]: Insecure sensitive password input element detected")
        }
        return forms
    }

    private fun extractScripts(html: String): List<String> {
        val scriptRegex = "<script\\s+(.*?)>".toRegex(RegexOption.IGNORE_CASE)
        val scripts = mutableListOf<String>()
        
        scriptRegex.findAll(html).take(10).forEach { match ->
            scripts.add(match.value)
        }

        // Search for inline code or critical signatures: eval, suspicious iframe injection, base64 strings
        if (html.contains("eval(", ignoreCase = true)) {
            scripts.add("[Heuristic Warning]: Inline presence of Javascript eval() function execution")
        }
        if (html.contains("document.write(unescape(", ignoreCase = true) || html.contains("unescape(atob(", ignoreCase = true)) {
            scripts.add("[Heuristic Warning]: Obfuscated script decryption signature (escape/unescape/atob) identified")
        }
        if (html.contains("coinhive.min.js", ignoreCase = true) || html.contains("cryptonight.asm.js", ignoreCase = true)) {
            scripts.add("[Heuristic Warning]: Crypto-jacking signature blocks matching CoinHive embedded script identifiers")
        }
        
        return scripts
    }
}
