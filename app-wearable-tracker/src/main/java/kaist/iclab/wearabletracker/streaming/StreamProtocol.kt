package kaist.iclab.wearabletracker.streaming

import kaist.iclab.tracker.sensor.core.SensorEntity
import kaist.iclab.tracker.sensor.galaxywatch.AccelerometerSensor
import kaist.iclab.tracker.sensor.galaxywatch.EDASensor
import kaist.iclab.tracker.sensor.galaxywatch.HeartRateSensor
import kaist.iclab.tracker.sensor.galaxywatch.IMUSensor
import kaist.iclab.tracker.sensor.galaxywatch.PPGSensor
import kaist.iclab.tracker.sensor.galaxywatch.SkinTemperatureSensor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * Wire format for the watch -> phone real-time stream: one JSON object per line,
 * enveloped as {"s":"<sensorId>","t":<wall-clock ms>,"d":{...entity...}}.
 *
 * The phone relays each line verbatim to the lab server, so this format is what
 * consumers on the PC side parse.
 */
object StreamProtocol {
    private val json = Json { encodeDefaults = true }

    /**
     * Encode one sensor entity as a single JSON line (without trailing newline).
     * Returns null for entity types that are not part of the stream.
     */
    fun encode(sensorId: String, entity: SensorEntity): String? {
        val data: JsonElement = when (entity) {
            is AccelerometerSensor.Entity -> json.encodeToJsonElement(entity)
            is PPGSensor.Entity -> json.encodeToJsonElement(entity)
            is HeartRateSensor.Entity -> json.encodeToJsonElement(entity)
            is SkinTemperatureSensor.Entity -> json.encodeToJsonElement(entity)
            is EDASensor.Entity -> json.encodeToJsonElement(entity)
            is IMUSensor.Entity -> json.encodeToJsonElement(entity)
            else -> return null
        }
        return buildJsonObject {
            put("s", sensorId)
            put("t", System.currentTimeMillis())
            put("d", data)
        }.toString()
    }
}
