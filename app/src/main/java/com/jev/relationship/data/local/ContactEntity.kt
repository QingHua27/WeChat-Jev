package com.jev.relationship.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jev.relationship.core.model.Contact

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val updatedAt: Long,
) {
    fun toDomain(): Contact = Contact(id = id, displayName = displayName)

    companion object {
        fun from(contact: Contact, updatedAt: Long): ContactEntity = ContactEntity(
            id = contact.id,
            displayName = contact.displayName,
            updatedAt = updatedAt,
        )
    }
}

