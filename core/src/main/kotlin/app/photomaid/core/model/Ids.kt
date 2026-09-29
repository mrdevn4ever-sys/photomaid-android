package app.photomaid.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@JvmInline
value class ReviewSessionID(@Serializable(UUIDSerializer::class) val rawValue: UUID = UUID.randomUUID())

@Serializable
@JvmInline
value class CommandID(@Serializable(UUIDSerializer::class) val rawValue: UUID = UUID.randomUUID())

@Serializable
@JvmInline
value class OperationID(@Serializable(UUIDSerializer::class) val rawValue: UUID = UUID.randomUUID())
