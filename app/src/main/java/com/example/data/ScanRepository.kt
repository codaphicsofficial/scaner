package com.example.data

import kotlinx.coroutines.flow.Flow

class ScanRepository(private val scanDao: ScanDao) {
    val allScans: Flow<List<ScanLog>> = scanDao.getAllScans()

    suspend fun insertScan(log: ScanLog) {
        scanDao.insertScan(log)
    }

    suspend fun deleteScan(log: ScanLog) {
        scanDao.deleteScan(log)
    }

    suspend fun clearAll() {
        scanDao.clearAll()
    }
}
