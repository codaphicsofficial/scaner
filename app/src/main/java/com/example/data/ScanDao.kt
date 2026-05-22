package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
    @Query("SELECT * FROM scan_logs ORDER BY timestamp DESC")
    fun getAllScans(): Flow<List<ScanLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(log: ScanLog)

    @Delete
    suspend fun deleteScan(log: ScanLog)

    @Query("DELETE FROM scan_logs")
    suspend fun clearAll()
}
