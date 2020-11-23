package no.nav.helse.prosessering.v1.asynkron.deleOmsorgsdager

import no.nav.helse.kafka.KafkaConfig
import no.nav.helse.kafka.ManagedKafkaStreams
import no.nav.helse.kafka.ManagedStreamHealthy
import no.nav.helse.kafka.ManagedStreamReady
import no.nav.helse.prosessering.v1.PreprosesseringV1Service
import no.nav.helse.prosessering.v1.asynkron.Topics
import no.nav.helse.prosessering.v1.asynkron.deserialiserTilMeldingDeleOmsorgsdagerV1
import no.nav.helse.prosessering.v1.asynkron.process
import no.nav.helse.prosessering.v1.asynkron.serialiserTilData
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.slf4j.LoggerFactory

internal class PreprosesseringStreamDeleOmsorgsdager(
    preprosesseringV1Service: PreprosesseringV1Service,
    kafkaConfig: KafkaConfig,
    autoOffsetResetDeleDager: String
) {
    private val stream = ManagedKafkaStreams(
        name = NAME,
        properties = kafkaConfig.stream(NAME).let {
            it.replace(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetResetDeleDager)
            it
        },
        topology = topology(preprosesseringV1Service),
        unreadyAfterStreamStoppedIn = kafkaConfig.unreadyAfterStreamStoppedIn
    )

    internal val ready = ManagedStreamReady(stream)
    internal val healthy = ManagedStreamHealthy(stream)

    private companion object {

        private const val NAME = "PreprosesseringStreamDeleOmsorgsdager"
        private val logger = LoggerFactory.getLogger("no.nav.$NAME.topology")

        private fun topology(preprosesseringV1Service: PreprosesseringV1Service): Topology {
            val builder = StreamsBuilder()
            val fromMottatt = Topics.MOTTATT_DELE_OMSORGSDAGER
            val tilPreprossesert = Topics.PREPROSESSERT_DELE_OMSORGSDAGER

            builder
                .stream(fromMottatt.name, fromMottatt.consumed)
                .filter { _, entry -> 1 == entry.metadata.version }
                .mapValues { soknadId, entry ->
                    process(NAME, soknadId, entry) {
                        logger.info(formaterStatuslogging(soknadId, "preprosesseres"))

                        val preprossesertMelding = preprosesseringV1Service.preprosesserMeldingDeleOmsorgsdager(
                            melding = entry.deserialiserTilMeldingDeleOmsorgsdagerV1(),
                            metadata = entry.metadata
                        )
                        preprossesertMelding.serialiserTilData()
                    }
                }
                .to(tilPreprossesert.name, tilPreprossesert.produced)
            return builder.build()
        }
    }

    internal fun stop() = stream.stop(becauseOfError = false)
}
