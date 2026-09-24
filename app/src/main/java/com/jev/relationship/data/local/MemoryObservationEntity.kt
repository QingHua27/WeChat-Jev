package com.jev.relationship.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jev.relationship.core.model.MemoryKind
import com.jev.relationship.core.model.MemoryObservation

@Entity(
    tableName = "memory_observations",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("contactId")],
)
data class MemoryObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: String,
    val kind: String,
    val text: String,
    val confidence: Double,
    val updatedAt: Long,
) {
    fun toDomain(): MemoryObservation = MemoryObservation(
        id = id,
        contactId = contactId,
        kind = runCatching { MemoryKind.valueOf(kind) }.getOrElse { MemoryKind.UserNote },
        text = text,
        confidence = confidence,
        updatedAt = updatedAt,
    )

    companion object {
        fun from(observation: MemoryObservation): MemoryObservationEntity = MemoryObservationEntity(
            id = observation.id,
            contactId = observation.contactId,
            kind = observation.kind.name,
            text = observation.text,
            confidence = observation.confidence,
            updatedAt = observation.updatedAt,
        )
    }
}

