package com.example.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.api.GeminiApiClient
import com.example.data.ScanDatabase
import com.example.data.ScanLog
import com.example.data.ScanRepository
import com.example.util.UrlContentFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShieldScanViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ScanRepository
    private val prefs: SharedPreferences = application.getSharedPreferences("shield_scan_prefs", Context.MODE_PRIVATE)

    // Exposed States
    val history: StateFlow<List<ScanLog>>
    
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _selectedLog = MutableStateFlow<ScanLog?>(null)
    val selectedLog: StateFlow<ScanLog?> = _selectedLog.asStateFlow()

    private val _showResultDialog = MutableStateFlow(false)
    val showResultDialog: StateFlow<Boolean> = _showResultDialog.asStateFlow()

    private val _isCameraScanning = MutableStateFlow(false)
    val isCameraScanning: StateFlow<String?> = MutableStateFlow(null) // Holds QR scanner state, null when closed: otherwise "ACTIVE" or "QR_DETECTED_ALERT"

    private val _activeQrUrl = MutableStateFlow<String?>(null)
    val activeQrUrl: StateFlow<String?> = _activeQrUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    init {
        val database = ScanDatabase.getDatabase(application)
        repository = ScanRepository(database.scanDao())
        
        // Load history as StateFlow
        history = repository.allScans.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Read saved API key
        _customApiKey.value = prefs.getString("gemini_api_key", "") ?: ""
    }

    fun updateUrlInput(url: String) {
        _currentUrl.value = url
    }

    fun updateCustomApiKey(key: String) {
        _customApiKey.value = key
        prefs.edit().putString("gemini_api_key", key).apply()
    }

    fun selectHistoryLog(log: ScanLog) {
        _selectedLog.value = log
        _showResultDialog.value = true
    }

    fun closeResultDialog() {
        _showResultDialog.value = false
        _selectedLog.value = null
    }

    fun startQrCamera() {
        _activeQrUrl.value = null
    }

    fun handleQrDetected(url: String) {
        _activeQrUrl.value = url
    }

    fun cancelQrScan() {
        _activeQrUrl.value = null
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun deleteLog(log: ScanLog) {
        viewModelScope.launch {
            repository.deleteScan(log)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    /**
     * Main Core Scanner logic: performs dual heuristic extraction and submits telemetry to Gemini 3.5 Flash for AI analysis
     */
    fun performSecurityScan(targetUrl: String, scanType: String = "URL") {
        val trimmedUrl = targetUrl.trim()
        if (trimmedUrl.isBlank()) {
            _errorMessage.value = "Please enter a valid URL website address"
            return
        }

        // Quick check for basic url syntax
        val sanitized = if (!trimmedUrl.startsWith("http://", ignoreCase = true) && 
            !trimmedUrl.startsWith("https://", ignoreCase = true)) {
            "https://$trimmedUrl"
        } else {
            trimmedUrl
        }

        if (!Patterns.WEB_URL.matcher(sanitized).matches()) {
            _errorMessage.value = "Invalid URL web structure. Please check and try again."
            return
        }

        val resolvedKey = GeminiApiClient.getResolvedApiKey(_customApiKey.value)
        if (resolvedKey.isEmpty()) {
            _errorMessage.value = "Gemini API credentials not found. Please set your own API key in the App Settings panel."
            return
        }

        _isScanning.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                // Step 1: Real-time network and heuristic fetching
                val pageInfo = UrlContentFetcher.fetchPageInfo(sanitized)

                // Structure form list info
                val formsString = if (pageInfo.forms.isNotEmpty()) {
                    pageInfo.forms.joinToString("\n")
                } else {
                    "No interactive submission input form blocks found."
                }

                // Structure script listing
                val scriptsString = if (pageInfo.scripts.isNotEmpty()) {
                    pageInfo.scripts.joinToString("\n")
                } else {
                    "No explicit interactive JavaScript tags found."
                }

                // Check HTTPS level in heuristics
                val httpsVulnerability = if (!pageInfo.isHttps) {
                    "[Heuristic Alert]: Insecure HTTP transmission protocol layer (Unencrypted traffic; high risk of eavesdropping/MITM Interception)"
                } else {
                    "HTTPS Layer Secured (Encrypted SSL channel present)"
                }

                // Step 2: Request real-time analysis from Gemini AI Specialist
                val currentKey = _customApiKey.value
                val reportResult = withContext(Dispatchers.IO) {
                    GeminiApiClient.scanUrl(
                        url = pageInfo.url,
                        pageTitle = pageInfo.title,
                        fetchedContent = pageInfo.rawHtmlSnippet,
                        formsInfo = formsString,
                        scriptsInfo = scriptsString + "\n" + httpsVulnerability,
                        customKey = currentKey
                    )
                }

                if (reportResult != null) {
                    // Step 3: Map result and save to Room persistence
                    val scanLog = ScanLog(
                        url = pageInfo.url,
                        safetyScore = reportResult.safetyScore,
                        riskCategory = reportResult.riskCategory,
                        scanType = scanType,
                        isPhishing = reportResult.isPhishing,
                        phishingReason = reportResult.phishingReason,
                        isMalware = reportResult.isMalware,
                        malwareReason = reportResult.malwareReason,
                        detectedScripts = reportResult.detectedScripts,
                        vulnerabilities = reportResult.vulnerabilities,
                        recommendations = reportResult.recommendations
                    )
                    
                    repository.insertScan(scanLog)
                    
                    // Selected log to trigger detailed safe dialog display
                    _selectedLog.value = scanLog
                    _showResultDialog.value = true
                } else {
                    // Try to compose a manual fallback threat response if the API call failed yet reached limits
                    _errorMessage.value = "AI Analysis returned empty or rate limits hit. Please check your credentials and internet."
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Error conducting safety scan: ${e.localizedMessage ?: "Unknown connection error"}"
            } finally {
                _isScanning.value = false
            }
        }
    }
}
