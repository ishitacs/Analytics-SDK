package com.example.analytics.internal

import com.example.analytics.EventType
import com.example.analytics.ReservedKeys
import org.json.JSONObject

internal const val SDK_VERSION = "0.1.0"
internal const val ENVELOPE_SCHEMA_VERSION = 1

/**
 * Wraps sanitized user properties in the SDK-owned envelope. User data only ever lives under
 * `properties`; every top-level field is written here and nowhere else.
 */
internal class EnvelopeBuilder(private val maxEventBytes: Int) {

    /** @return the serialized envelope, or null if it exceeds the size limit. */
    fun build(
        eventId: String,
        name: String,
        type: EventType,
        properties: JSONObject,
        identity: Identity,
        sessionId: String?,
        seq: Long,
        clientTs: Long,
        elapsedRealtime: Long,
        device: DeviceContext,
        networkType: String,
        sampleRate: Double? = null,
    ): String? {
        val envelope = JSONObject()
            .put(ReservedKeys.EVENT_ID, eventId)
            .put(ReservedKeys.NAME, name)
            .put(ReservedKeys.TYPE, type.wireName)
            .put(ReservedKeys.PROPERTIES, properties)
            .put(ReservedKeys.USER_ID, identity.userId ?: JSONObject.NULL)
            .put(ReservedKeys.ANONYMOUS_ID, identity.anonymousId)
            .put(ReservedKeys.DEVICE_ID, identity.deviceId)
            .put(ReservedKeys.SESSION_ID, sessionId ?: JSONObject.NULL)
            .put(ReservedKeys.SEQ, seq)
            .put(ReservedKeys.CLIENT_TS, clientTs)
            .put(ReservedKeys.ELAPSED_REALTIME, elapsedRealtime)
            .put(ReservedKeys.APP_VERSION, device.appVersion)
            .put(ReservedKeys.SDK_VERSION, SDK_VERSION)
            .put(ReservedKeys.OS_VERSION, device.osVersion)
            .put(ReservedKeys.SCHEMA_VERSION, ENVELOPE_SCHEMA_VERSION)
            .put(ReservedKeys.CONTEXT, device.toJson(networkType).apply { sampleRate?.let { put("sample_rate", it) } })
        val json = envelope.toString()
        // Cheap upper bound first: a UTF-8 char is at most 3 bytes for BMP text.
        if (json.length * 3 <= maxEventBytes) return json
        return if (json.toByteArray(Charsets.UTF_8).size <= maxEventBytes) json else null
    }
}
