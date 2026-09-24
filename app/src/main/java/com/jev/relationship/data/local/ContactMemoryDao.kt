package com.jev.relationship.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(contact: ContactEntity)

    @Query("SELECT * FROM contacts ORDER BY updatedAt DESC")
    fun observeContacts(): Flow<List<ContactEntity>>

    @Query("DELETE FROM contacts WHERE id = :contactId")
    suspend fun deleteContact(contactId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertObservation(observation: MemoryObservationEntity): Long

    @Query("SELECT * FROM memory_observations WHERE contactId = :contactId ORDER BY updatedAt DESC")
    fun observeMemory(contactId: String): Flow<List<MemoryObservationEntity>>

    @Query("DELETE FROM memory_observations WHERE id = :observationId")
    suspend fun deleteObservation(observationId: Long)
}

