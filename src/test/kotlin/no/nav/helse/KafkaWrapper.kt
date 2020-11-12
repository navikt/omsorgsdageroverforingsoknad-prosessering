package no.nav.helse

import no.nav.common.JAASCredential
import no.nav.common.KafkaEnvironment
import no.nav.helse.prosessering.Metadata
import no.nav.helse.prosessering.v1.asynkron.Data
import no.nav.helse.prosessering.v1.asynkron.TopicEntry
import no.nav.helse.prosessering.v1.asynkron.Topics.CLEANUP_DELE_OMSORGSDAGER
import no.nav.helse.prosessering.v1.asynkron.Topics.CLEANUP_OVERFOREDAGER
import no.nav.helse.prosessering.v1.asynkron.Topics.JOURNALFORT_OVERFOREDAGER
import no.nav.helse.prosessering.v1.asynkron.Topics.K9_RAPID_V2
import no.nav.helse.prosessering.v1.asynkron.Topics.MOTTATT_DELE_OMSORGSDAGER
import no.nav.helse.prosessering.v1.asynkron.Topics.MOTTATT_OVERFOREDAGER
import no.nav.helse.prosessering.v1.asynkron.Topics.PREPROSESSERT_DELE_OMSORGSDAGER
import no.nav.helse.prosessering.v1.asynkron.Topics.PREPROSSESERT_OVERFOREDAGER
import no.nav.helse.prosessering.v1.deleOmsorgsdager.MeldingDeleOmsorgsdagerV1
import no.nav.helse.prosessering.v1.overforeDager.SøknadOverføreDagerV1
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.*
import kotlin.test.assertEquals

private const val username = "srvkafkaclient"
private const val password = "kafkaclient"

object KafkaWrapper {
    fun bootstrap(): KafkaEnvironment {
        val kafkaEnvironment = KafkaEnvironment(
            users = listOf(JAASCredential(username, password)),
            autoStart = true,
            withSchemaRegistry = false,
            withSecurity = true,
            topicNames = listOf(
                MOTTATT_OVERFOREDAGER.name,
                PREPROSSESERT_OVERFOREDAGER.name,
                JOURNALFORT_OVERFOREDAGER.name,
                CLEANUP_OVERFOREDAGER.name,
                MOTTATT_DELE_OMSORGSDAGER.name,
                PREPROSESSERT_DELE_OMSORGSDAGER.name,
                CLEANUP_DELE_OMSORGSDAGER.name,
                K9_RAPID_V2.name
            )
        )
        return kafkaEnvironment
    }
}

private fun KafkaEnvironment.testConsumerProperties(groupId: String): MutableMap<String, Any>? {
    return HashMap<String, Any>().apply {
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, brokersURL)
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
        put(SaslConfigs.SASL_MECHANISM, "PLAIN")
        put(
            SaslConfigs.SASL_JAAS_CONFIG,
            "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";"
        )
        put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    }
}

private fun KafkaEnvironment.testProducerProperties(clientId: String): MutableMap<String, Any>? {
    return HashMap<String, Any>().apply {
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, brokersURL)
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
        put(SaslConfigs.SASL_MECHANISM, "PLAIN")
        put(
            SaslConfigs.SASL_JAAS_CONFIG,
            "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";"
        )
        put(ProducerConfig.CLIENT_ID_CONFIG, clientId)
    }
}

fun KafkaEnvironment.journalføringsKonsumerOverforeDager(): KafkaConsumer<String, String> {
    val consumer = KafkaConsumer(
        testConsumerProperties("OverforeDagerKonsumer"),
        StringDeserializer(),
        StringDeserializer()
    )
    consumer.subscribe(listOf(JOURNALFORT_OVERFOREDAGER.name))
    return consumer
}

fun KafkaEnvironment.k9RapidConsumer(): KafkaConsumer<String, String> {
    val consumer = KafkaConsumer(
        testConsumerProperties("K9 Rapid Consumer"),
        StringDeserializer(),
        StringDeserializer()
    )
    consumer.subscribe(listOf(K9_RAPID_V2.name))
    return consumer
}

fun KafkaEnvironment.meldingOverforeDagersProducer() = KafkaProducer(
    testProducerProperties("OmsorgspengesoknadOverføreDagerProsesseringTestProducer"),
    MOTTATT_OVERFOREDAGER.keySerializer,
    MOTTATT_OVERFOREDAGER.serDes
)

fun KafkaEnvironment.meldingDeleOmsorgsdagerProducer() = KafkaProducer(
    testProducerProperties("DeleOmsorgsdagerProsesseringTestProducer"),
    MOTTATT_DELE_OMSORGSDAGER.keySerializer,
    MOTTATT_DELE_OMSORGSDAGER.serDes
)

fun KafkaConsumer<String, String>.hentJournalførtMeldingOverforeDager(
    soknadId: String,
    maxWaitInSeconds: Long = 20
): String {
    val end = System.currentTimeMillis() + Duration.ofSeconds(maxWaitInSeconds).toMillis()
    while (System.currentTimeMillis() < end) {
        seekToBeginning(assignment())
        val entries = poll(Duration.ofSeconds(1))
            .records(JOURNALFORT_OVERFOREDAGER.name)
            .filter { it.key() == soknadId }

        if (entries.isNotEmpty()) {
            assertEquals(1, entries.size)
            return entries.first().value()
        }
    }
    throw IllegalStateException("Fant ikke opprettet oppgave for søknad $soknadId etter $maxWaitInSeconds sekunder.")
}

fun KafkaConsumer<String, String>.hentK9RapidMelding(
    id: String,
    maxWaitInSeconds: Long = 20
): String {
    val end = System.currentTimeMillis() + Duration.ofSeconds(maxWaitInSeconds).toMillis()
    while (System.currentTimeMillis() < end) {
        seekToBeginning(assignment())
        val entries = poll(Duration.ofSeconds(1))
            .records(K9_RAPID_V2.name).toList()
            .filter { it.key() == id }

        if (entries.isNotEmpty()) {
            assertEquals(1, entries.size)
            return entries.first().value()
        }
    }
    throw IllegalStateException("Fant ikke opprettet oppgave for melding med id: $id etter $maxWaitInSeconds sekunder.")
}

fun KafkaProducer<String, TopicEntry>.leggTilMottak(soknad: SøknadOverføreDagerV1) {
    send(
        ProducerRecord(
            MOTTATT_OVERFOREDAGER.name,
            soknad.søknadId,
            TopicEntry(
                metadata = Metadata(
                    version = 2,
                    correlationId = UUID.randomUUID().toString(),
                    requestId = UUID.randomUUID().toString()
                ),
                data = Data(omsorgsdageroverførningKonfigurertMapper().writeValueAsString(soknad))
            )
        )
    ).get()
}

fun KafkaProducer<String, TopicEntry>.leggTilMottakDeleOmsorgsdager(soknad: MeldingDeleOmsorgsdagerV1) {
    send(
        ProducerRecord(
            MOTTATT_DELE_OMSORGSDAGER.name,
            soknad.søknadId,
            TopicEntry(
                metadata = Metadata(
                    version = 1,
                    correlationId = UUID.randomUUID().toString(),
                    requestId = UUID.randomUUID().toString()
                ),
                data = Data(omsorgsdageroverførningKonfigurertMapper().writeValueAsString(soknad))
            )
        )
    ).get()
}

fun KafkaEnvironment.username() = username
fun KafkaEnvironment.password() = password
