package no.nav.helse.prosessering.v1.asynkron.deleOmsorgsdager

import no.nav.helse.CorrelationId
import no.nav.helse.aktoer.AktørId
import no.nav.helse.joark.JoarkGateway
import no.nav.helse.joark.Navn
import no.nav.helse.kafka.KafkaConfig
import no.nav.helse.kafka.ManagedKafkaStreams
import no.nav.helse.kafka.ManagedStreamHealthy
import no.nav.helse.kafka.ManagedStreamReady
import no.nav.helse.prosessering.v1.asynkron.*
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.slf4j.LoggerFactory

internal class JournalforingsStreamDeleOmsorgsdager(
    joarkGateway: JoarkGateway,
    kafkaConfig: KafkaConfig
) {

    private val stream = ManagedKafkaStreams(
        name = NAME,
        properties = kafkaConfig.stream(NAME),
        topology = topology(joarkGateway),
        unreadyAfterStreamStoppedIn = kafkaConfig.unreadyAfterStreamStoppedIn
    )

    internal val ready = ManagedStreamReady(stream)
    internal val healthy = ManagedStreamHealthy(stream)

    private companion object {
        private const val NAME = "JournalforingV1DeleOmsorgsdager"
        private val logger = LoggerFactory.getLogger("no.nav.$NAME.topology")

        private fun topology(joarkGateway: JoarkGateway): Topology {
            val builder = StreamsBuilder()
            val fraPreprosessertV1 = Topics.PREPROSESSERT_DELE_OMSORGSDAGER
            val tilCleanup = Topics.CLEANUP_DELE_OMSORGSDAGER

            val mapValues = builder
                .stream(fraPreprosessertV1.name, fraPreprosessertV1.consumed)
                .filter { _, entry -> 1 == entry.metadata.version }
                .mapValues { soknadId, entry ->
                    process(NAME, soknadId, entry) {
                        logger.info(formaterStatuslogging(soknadId, "journalføres"))

                        val preprosessertMelding = entry.deserialiserTilPreprosessertDeleOmsorgsdagerV1()

                        val dokumenter = preprosessertMelding.dokumentUrls
                        logger.trace("Journalfører deling av omsorgsdager dokumenter: {}", dokumenter)

                        val journalPostId = joarkGateway.journalførOverforeDager(
                            mottatt = preprosessertMelding.mottatt,
                            aktørId = AktørId(preprosessertMelding.søker.aktørId),
                            norskIdent = preprosessertMelding.søker.fødselsnummer,
                            correlationId = CorrelationId(entry.metadata.correlationId),
                            dokumenter = dokumenter,
                            navn = Navn(
                                fornavn = preprosessertMelding.søker.fornavn,
                                mellomnavn = preprosessertMelding.søker.mellomnavn,
                                etternavn = preprosessertMelding.søker.etternavn
                            )
                        )

                        logger.trace("Dokumenter til deling av  omsorgsdager journalført med ID = ${journalPostId.journalpostId}.")

                        CleanupDeleOmsorgsdager(
                            metadata = entry.metadata,
                            meldingV1 = preprosessertMelding,
                            journalpostId = journalPostId.journalpostId
                        ).serialiserTilData()
                    }
                }
            mapValues
                .to(tilCleanup.name, tilCleanup.produced)
            return builder.build()
        }
    }

    internal fun stop() = stream.stop(becauseOfError = false)
}
