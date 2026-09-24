package com.jev.relationship.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AnalysisEntity): Long

    @Query("SELECT * FROM analysis_history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analysis_history WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): AnalysisEntity?

    @Query("DELETE FROM analysis_history WHERE id = :id")
    suspend fun deleteById(id: Long)
}

