package no.nav.helse.prosessering.v1.asynkron.overforeDager

import no.nav.helse.erEtter
import no.nav.helse.kafka.KafkaConfig
import no.nav.helse.kafka.ManagedKafkaStreams
import no.nav.helse.kafka.ManagedStreamHealthy
import no.nav.helse.kafka.ManagedStreamReady
import no.nav.helse.prosessering.v1.PreprosseseringV1Service
import no.nav.helse.prosessering.v1.asynkron.*
import no.nav.helse.prosessering.v1.asynkron.Topics
import no.nav.helse.prosessering.v1.asynkron.deserialiserTilSøknadOverføreDagerV1
import no.nav.helse.prosessering.v1.asynkron.process
import no.nav.helse.prosessering.v1.asynkron.serialiserTilData
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime

internal class PreprosseseringStreamOverforeDager(
    preprosseseringV1Service: PreprosseseringV1Service,
    kafkaConfig: KafkaConfig,
    datoMottattEtter: ZonedDateTime
) {
    private val stream = ManagedKafkaStreams(
        name = NAME,
        properties = kafkaConfig.stream(NAME),
        topology = topology(preprosseseringV1Service, datoMottattEtter),
        unreadyAfterStreamStoppedIn = kafkaConfig.unreadyAfterStreamStoppedIn
    )

    internal val ready = ManagedStreamReady(stream)
    internal val healthy = ManagedStreamHealthy(stream)

    private companion object {

        private const val NAME = "PreprosesseringV1OverforeDager"
        private val logger = LoggerFactory.getLogger("no.nav.$NAME.topology")

        private fun topology(preprosseseringV1Service: PreprosseseringV1Service, gittDato: ZonedDateTime): Topology {
            val builder = StreamsBuilder()
            val fromMottatt = Topics.MOTTATT_OVERFOREDAGER
            val tilPreprossesert = Topics.PREPROSSESERT_OVERFOREDAGER

            builder
                .stream(fromMottatt.name, fromMottatt.consumed)
                .filter { _, entry -> entry.deserialiserTilSøknadOverføreDagerV1().mottatt.erEtter(gittDato) }
                .filter { _, entry -> 2 == entry.metadata.version }
                .mapValues { soknadId, entry ->
                    process(NAME, soknadId, entry) {
                        logger.info("Preprosesserer søknad for overføring av dager.")
                        val preprossesertMelding = preprosseseringV1Service.preprosseserOverforeDager(
                            melding = entry.deserialiserTilSøknadOverføreDagerV1(),
                            metadata = entry.metadata
                        )
                        logger.info("Preprossesering søknad overføre dager ferdig.")
                        preprossesertMelding.serialiserTilData()
                    }
                }
                .to(tilPreprossesert.name, tilPreprossesert.produced)
            return builder.build()
        }
    }

    internal fun stop() = stream.stop(becauseOfError = false)
}
