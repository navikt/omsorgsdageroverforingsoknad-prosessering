package no.nav.helse.prosessering.v1.asynkron

import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.helse.omsorgsdageroverførningKonfigurertMapper
import no.nav.helse.prosessering.Metadata
import no.nav.helse.prosessering.v1.deleOmsorgsdager.MeldingDeleOmsorgsdagerV1
import no.nav.helse.prosessering.v1.deleOmsorgsdager.PreprosessertDeleOmsorgsdagerV1
import no.nav.helse.prosessering.v1.overforeDager.PreprossesertOverforeDagerV1
import no.nav.helse.prosessering.v1.overforeDager.SøknadOverføreDagerV1
import no.nav.k9.søknad.omsorgspenger.overføring.OmsorgspengerOverføringSøknad
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Produced
import org.json.JSONObject

internal data class Topic(
    val name: String,
    val serDes: SerDes
) {
    val keySerializer = StringSerializer()
    private val keySerde = Serdes.String()
    private val valueSerde = Serdes.serdeFrom(SerDes(), SerDes())
    val consumed = Consumed.with(keySerde, valueSerde)
    val produced = Produced.with(keySerde, valueSerde)
}

internal object Topics {
    val MOTTATT_OVERFOREDAGER = Topic(
        name = "privat-overfore-omsorgsdager-soknad-mottatt",
        serDes = SerDes()
    )

    val PREPROSSESERT_OVERFOREDAGER = Topic(
        name = "privat-overfore-omsorgsdager-soknad-preprossesert",
        serDes = SerDes()
    )

    val CLEANUP_OVERFOREDAGER = Topic(
        name = "privat-overfore-omsorgsdager-soknad-cleanup",
        serDes = SerDes()
    )

    val JOURNALFORT_OVERFOREDAGER = Topic(
        name = "privat-overfore-omsorgsdager-soknad-journalfort",
        serDes = SerDes()
    )

    val MOTTATT_DELE_OMSORGSDAGER = Topic(
        name = "privat-dele-omsorgsdager-melding-mottatt",
        serDes = SerDes()
    )

    val PREPROSESSERT_DELE_OMSORGSDAGER = Topic(
        name = "privat-dele-omsorgsdager-melding-preprosessert",
        serDes = SerDes()
    )

    val CLEANUP_DELE_OMSORGSDAGER = Topic(
        name = "privat-dele-omsorgsdager-melding-cleanup",
        serDes = SerDes()
    )

    val K9_RAPID_V2 = Topic(
        name = "k9-rapid-v2",
        serDes = SerDes()
    )

}

data class Data(val rawJson: String)
data class CleanupOverforeDager(
    val metadata: Metadata,
    val meldingV1: PreprossesertOverforeDagerV1,
    val journalførtMelding: JournalfortOverforeDager
)

data class CleanupDeleOmsorgsdager(
    val metadata: Metadata,
    val meldingV1: PreprosessertDeleOmsorgsdagerV1,
    val journalførtMelding: JournalfortDeleDager
)

class SerDes : Serializer<TopicEntry>, Deserializer<TopicEntry> {
    override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {}
    override fun close() {}
    override fun serialize(topic: String, entry: TopicEntry): ByteArray = when (topic == Topics.K9_RAPID_V2.name) {
        true -> entry.data.rawJson.toByteArray()
        false -> entry.rawJson.toByteArray()
    }
    override fun deserialize(topic: String, entry: ByteArray): TopicEntry = TopicEntry(String(entry))
}

internal fun TopicEntry.deserialiserTilSøknadOverføreDagerV1(): SøknadOverføreDagerV1 = omsorgsdageroverførningKonfigurertMapper().readValue(data.rawJson)
internal fun TopicEntry.deserialiserTilPreprossesertOverforeDagerV1():PreprossesertOverforeDagerV1  = omsorgsdageroverførningKonfigurertMapper().readValue(data.rawJson)
internal fun TopicEntry.deserialiserTilCleanupOverforeDager():CleanupOverforeDager  = omsorgsdageroverførningKonfigurertMapper().readValue(data.rawJson)

internal fun TopicEntry.deserialiserTilMeldingDeleOmsorgsdagerV1(): MeldingDeleOmsorgsdagerV1 = omsorgsdageroverførningKonfigurertMapper().readValue(data.rawJson)
internal fun TopicEntry.deserialiserTilPreprosessertDeleOmsorgsdagerV1():PreprosessertDeleOmsorgsdagerV1  = omsorgsdageroverførningKonfigurertMapper().readValue(data.rawJson)
internal fun TopicEntry.deserialiserTilCleanupDeleOmsorgsdager():CleanupDeleOmsorgsdager  = omsorgsdageroverførningKonfigurertMapper().readValue(data.rawJson)


internal fun Any.serialiserTilData() = Data(omsorgsdageroverførningKonfigurertMapper().writeValueAsString(this))


data class JournalfortOverforeDager(val journalpostId: String, val søknad: OmsorgspengerOverføringSøknad)
data class JournalfortDeleDager(val journalpostId: String)

data class TopicEntry(val rawJson: String) {
    constructor(metadata: Metadata, data: Data) : this(
        JSONObject(
            mapOf(
                "metadata" to JSONObject(
                    mapOf(
                        "version" to metadata.version,
                        "correlationId" to metadata.correlationId,
                        "requestId" to metadata.requestId
                    )
                ),
                "data" to JSONObject(data.rawJson)
            )
        ).toString()
    )

    private val entityJson = JSONObject(rawJson)
    private val metadataJson = requireNotNull(entityJson.getJSONObject("metadata"))
    private val dataJson = requireNotNull(entityJson.getJSONObject("data"))
    val metadata = Metadata(
        version = requireNotNull(metadataJson.getInt("version")),
        correlationId = requireNotNull(metadataJson.getString("correlationId")),
        requestId = requireNotNull(metadataJson.getString("requestId"))
    )
    val data = Data(dataJson.toString())
}
