package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_logs")
data class ScanLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),
    val safetyScore: Int, // 0 (Malicious/Phishing) to 100 (Safe)
    val riskCategory: String, // "SAFE", "SUSPICIOUS", "DANGEROUS"
    val scanType: String, // "URL" or "QR"
    val isPhishing: Boolean,
    val phishingReason: String,
    val isMalware: Boolean,
    val malwareReason: String,
    val detectedScripts: String, // JSON or comma-separated list of scripts/technologies scanned
    val vulnerabilities: String, // Vulnerabilities found (XSS, missing HTTPS, etc.)
    val recommendations: String // Markdown-ready summary details or bullets
)
