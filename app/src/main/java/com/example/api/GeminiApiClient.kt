package com.example.api

import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Gemini Request Data Classes ---

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    @Json(name = "contents") val contents: List<Content>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    @Json(name = "parts") val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    @Json(name = "text") val text: String? = null
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    @Json(name = "responseMimeType") val responseMimeType: String? = null,
    @Json(name = "temperature") val temperature: Double? = null
)

// --- Gemini Response Data Classes ---

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    @Json(name = "candidates") val candidates: List<Candidate>? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    @Json(name = "content") val content: Content? = null
)

// --- Structured Security Report Representation ---

@JsonClass(generateAdapter = true)
data class SecurityReport(
    @Json(name = "safetyScore") val safetyScore: Int,
    @Json(name = "riskCategory") val riskCategory: String, // "SAFE", "SUSPICIOUS", "DANGEROUS"
    @Json(name = "isPhishing") val isPhishing: Boolean,
    @Json(name = "phishingReason") val phishingReason: String,
    @Json(name = "isMalware") val isMalware: Boolean,
    @Json(name = "malwareReason") val malwareReason: String,
    @Json(name = "detectedScripts") val detectedScripts: String,
    @Json(name = "vulnerabilities") val vulnerabilities: String,
    @Json(name = "recommendations") val recommendations: String
)

interface GeminiApiService {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object GeminiApiClient {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val service: GeminiApiService = retrofit.create(GeminiApiService::class.java)

    /**
     * Resolves the API Key to use (prioritizing custom key user supplied, then environment BuildConfig)
     */
    fun getResolvedApiKey(customKey: String?): String {
        val buildKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
        return when {
            !customKey.isNullOrBlank() -> customKey.trim()
            buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY" -> buildKey.trim()
            else -> ""
        }
    }

    suspend fun scanUrl(
        url: String,
        pageTitle: String?,
        fetchedContent: String?,
        formsInfo: String?,
        scriptsInfo: String?,
        customKey: String?
    ): SecurityReport? {
        val apiKey = getResolvedApiKey(customKey)
        if (apiKey.isEmpty()) return null

        val prompt = if (fetchedContent != null) {
            """
            Analyze the following webpage loaded in real-time for security risks, phishing, malicious scripts, and potential vulnerabilities.
            
            [Target URL]: $url
            [Page Title]: $pageTitle
            [Form Structs / Inputs Found]: $formsInfo
            [Inline / External Scripts Found]: $scriptsInfo
            [Truncated Page HTML Content snippet]:
            $fetchedContent
            
            Perform a thorough check for:
            1. Phishing: spoofing popular services, social engineering, insecure login forms over HTTP or unrecognized actions, typosquatting domains.
            2. Malware scripts: cryptocurrency mining, copy-paste hijackers, unescaped scripts, hidden redirects, script-injection loops, clickjacking iframes.
            3. Vulnerabilities: missing secure cookies (or configuration), scripts loaded from sketchy domains, unencrypted data submission.
            
            Return a JSON object matching this schema:
            {
               "safetyScore": <Int between 0 and 100, where 100 is completely safe, 0 is active scam/malware>,
               "riskCategory": "<"SAFE" or "SUSPICIOUS" or "DANGEROUS">",
               "isPhishing": <Boolean>,
               "phishingReason": "<Concrete reason or details. Mention if safe. Keep concise.>",
               "isMalware": <Boolean>,
               "malwareReason": "<Concrete proof, suspicious script names, or signature patterns. Mention if safe.>",
               "detectedScripts": "<Brief comma separated list of web libraries, libraries, trackers, or suspicious script blocks detected>",
               "vulnerabilities": "<Specific security weaknesses found (e.g. Insecure HTTP submittal, external sketchy script origin)>",
               "recommendations": "<Provide bullet points of instructions for a non-technical user regarding this URL, using Markdown format.>"
            }
            
            Do not include any explanation or backticks. Return ONLY the JSON.
            """.trimIndent()
        } else {
            """
            Analyze the following URL for potential threat indicators, typosquatting, phishing risk, and reputation. 
            NOTE: This site could not be direct-fetched (maybe secure, offline, or requires authentication/is geo-blocked). Run analysis on the URL string itself.
            
            [Target URL]: $url
            
            Analyze the domain, TLD, subdomain structure, and look for:
            1. Phishing: brand mimicking (homoglyphs, typos (e.g. paypa1, google-sec, etc.)), subdomain nesting (e.g. apple.com.auth-secure-login.xyz).
            2. Suspect TLDs: .tk, .cc, .ru, .xyz, .top, etc. which has disproportionate levels of suspicious activity.
            3. Vulnerability Indicators: lack of HTTPS, unconventional ports, unmasked file extensions (e.g. .exe, .sh) inside query fields.
            
            Return a JSON object conforming to this schema:
            {
               "safetyScore": <Int between 0 and 100, where 100 is completely safe, 0 is malicious>,
               "riskCategory": "<"SAFE" or "SUSPICIOUS" or "DANGEROUS">",
               "isPhishing": <Boolean>,
               "phishingReason": "<Concrete reason why the domain looks typosquatted, brand-copycatting, or secure. Keep concise.>",
               "isMalware": <Boolean>,
               "malwareReason": "<Check if URL path suggests malicious file download or drive-by injection.>",
               "detectedScripts": "None (Target un-fetchable, analyzed URL structure only)",
               "vulnerabilities": "<Specific safety concerns on metadata (e.g. Insecure HTTPS, suspicious TLD, subdomain stacking)>",
               "recommendations": "<Provide bullet points of safety instructions regarding this URL, using Markdown format.>"
            }
            
            Do not include any explanation or backticks. Return ONLY the JSON.
            """.trimIndent()
        }

        val request = GenerateContentRequest(
            contents = listOf(
                Content(parts = listOf(Part(text = prompt)))
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.1
            ),
            systemInstruction = Content(
                parts = listOf(Part(text = "You are a cyber security analysis engine that excels at analyzing URL structures, code script sources, and webpage heuristics for real-time safety classification. You always return perfect JSON outputs."))
            )
        )

        return try {
            val response = service.generateContent("gemini-3.5-flash", apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                // Parse report
                val cleanedJson = jsonText.trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                moshi.adapter(SecurityReport::class.java).fromJson(cleanedJson)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
