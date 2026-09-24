package com.jev.relationship.data.local

import com.jev.relationship.core.model.Contact
import com.jev.relationship.core.model.MemoryObservation
import com.jev.relationship.domain.ContactMemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomContactMemoryRepository(
    private val dao: ContactMemoryDao,
) : ContactMemoryRepository {
    override fun observeContacts(): Flow<List<Contact>> = dao.observeContacts().map { contacts ->
        contacts.map(ContactEntity::toDomain)
    }

    override suspend fun saveContact(contact: Contact) {
        dao.upsertContact(ContactEntity.from(contact, System.currentTimeMillis()))
    }

    override suspend fun deleteContact(contactId: String) = dao.deleteContact(contactId)

    override fun observeMemory(contactId: String): Flow<List<MemoryObservation>> =
        dao.observeMemory(contactId).map { observations -> observations.map(MemoryObservationEntity::toDomain) }

    override suspend fun saveObservation(observation: MemoryObservation): Long =
        dao.upsertObservation(MemoryObservationEntity.from(observation))

    override suspend fun deleteObservation(observationId: Long) = dao.deleteObservation(observationId)
}

