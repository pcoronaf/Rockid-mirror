package com.rokidmirror.protocol.control

import com.rokidmirror.protocol.Protocol
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Control message types. Names are the wire values. */
enum class MessageType {
    HELLO, HELLO_ACK, PAIR_REQUEST, PAIR_CONFIRM, CAPABILITIES,
    STREAM_START, STREAM_STOP, STREAM_FORMAT, VIEWPORT_SET, PROFILE_SET,
    KEYFRAME_REQUEST, PING, PONG, STATS, ERROR, GOODBYE;
}

/**
 * Envelope of every control message. `payload` is an opaque JSON object whose schema depends on
 * [type]; see [Payloads] for the typed models and protocol/schema for the JSON schema.
 */
@Serializable
data class ControlMessage(
    val protocolVersion: Int = Protocol.PROTOCOL_VERSION,
    val type: MessageType,
    val sessionId: String,
    val sequence: Long,
    val timestampNs: Long,
    val payload: JsonObject = JsonObject(emptyMap()),
)

/** JSON codec for control messages. Lenient towards unknown fields for forward compatibility. */
object ControlCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(message: ControlMessage): ByteArray = json.encodeToString(ControlMessage.serializer(), message).encodeToByteArray()

    /** @throws MirrorException with PROTOCOL_VERSION_MISMATCH or UNKNOWN on malformed input. */
    fun decode(bytes: ByteArray): ControlMessage {
        val message = try {
            json.decodeFromString(ControlMessage.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw MirrorException(ErrorCode.UNKNOWN, "malformed control message", e)
        } catch (e: IllegalArgumentException) {
            throw MirrorException(ErrorCode.UNKNOWN, "malformed control message", e)
        }
        if (message.protocolVersion != Protocol.PROTOCOL_VERSION) {
            throw MirrorException(ErrorCode.PROTOCOL_VERSION_MISMATCH, "peer protocol ${message.protocolVersion}")
        }
        return message
    }

    fun <T> payloadOf(message: ControlMessage, serializer: KSerializer<T>): T = try {
        json.decodeFromJsonElement(serializer, message.payload)
    } catch (e: SerializationException) {
        throw MirrorException(ErrorCode.UNKNOWN, "malformed ${message.type} payload", e)
    } catch (e: IllegalArgumentException) {
        throw MirrorException(ErrorCode.UNKNOWN, "malformed ${message.type} payload", e)
    }

    fun <T> toPayload(serializer: KSerializer<T>, value: T): JsonObject = json.encodeToJsonElement(serializer, value).jsonObject
}

/**
 * Builds sequenced, timestamped messages for one session. Not thread-safe by itself; callers
 * serialize sends anyway because the channel is a single ordered stream.
 */
class MessageFactory(val sessionId: String, private val clockNs: () -> Long) {
    private var sequence: Long = 0

    fun <T> create(type: MessageType, serializer: KSerializer<T>, payload: T): ControlMessage =
        ControlMessage(
            type = type,
            sessionId = sessionId,
            sequence = ++sequence,
            timestampNs = clockNs(),
            payload = ControlCodec.toPayload(serializer, payload),
        )

    fun create(type: MessageType): ControlMessage =
        ControlMessage(type = type, sessionId = sessionId, sequence = ++sequence, timestampNs = clockNs())
}
