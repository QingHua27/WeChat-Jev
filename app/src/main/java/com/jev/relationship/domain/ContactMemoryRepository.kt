package com.jev.relationship.domain

import com.jev.relationship.core.model.Contact
import com.jev.relationship.core.model.MemoryObservation
import kotlinx.coroutines.flow.Flow

interface ContactMemoryRepository {
    fun observeContacts(): Flow<List<Contact>>

    suspend fun saveContact(contact: Contact)

    suspend fun deleteContact(contactId: String)

    fun observeMemory(contactId: String): Flow<List<MemoryObservation>>

    suspend fun saveObservation(observation: MemoryObservation): Long

    suspend fun deleteObservation(observationId: Long)
}

