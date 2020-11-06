package no.nav.helse.prosessering.v1.asynkron.overforeDager

import no.nav.helse.CorrelationId
import no.nav.helse.aktoer.AktørId
import no.nav.helse.dokument.DokumentService
import no.nav.helse.erEtter
import no.nav.helse.kafka.KafkaConfig
import no.nav.helse.kafka.ManagedKafkaStreams
import no.nav.helse.kafka.ManagedStreamHealthy
import no.nav.helse.kafka.ManagedStreamReady
import no.nav.helse.prosessering.v1.asynkron.Topics
import no.nav.helse.prosessering.v1.asynkron.deserialiserTilCleanupOverforeDager
import no.nav.helse.prosessering.v1.asynkron.process
import no.nav.helse.prosessering.v1.asynkron.serialiserTilData
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime

internal class CleanupStreamOverforeDager(
    kafkaConfig: KafkaConfig,
    dokumentService: DokumentService,
    datoMottattEtter: ZonedDateTime
) {
    private val stream = ManagedKafkaStreams(
        name = NAME,
        properties = kafkaConfig.stream(NAME),
        topology = topology(dokumentService, datoMottattEtter),
        unreadyAfterStreamStoppedIn = kafkaConfig.unreadyAfterStreamStoppedIn
    )

    internal val ready = ManagedStreamReady(stream)
    internal val healthy = ManagedStreamHealthy(stream)

    private companion object {
        private const val NAME = "CleanupV1OverforeDager"
        private val logger = LoggerFactory.getLogger("no.nav.$NAME.topology")

        private fun topology(dokumentService: DokumentService, gittDato: ZonedDateTime): Topology {
            val builder = StreamsBuilder()
            val fraCleanup = Topics.CLEANUP_OVERFOREDAGER
            val tilJournalfort= Topics.JOURNALFORT_OVERFOREDAGER

            builder
                .stream(fraCleanup.name, fraCleanup.consumed)
                .filter { _, entry -> entry.deserialiserTilCleanupOverforeDager().meldingV1.mottatt.erEtter(gittDato) }
                .filter { _, entry -> 2 == entry.metadata.version }
                .mapValues { soknadId, entry ->
                    process(NAME, soknadId, entry) {
                        val cleanupMelding = entry.deserialiserTilCleanupOverforeDager()
                        logger.info("Sletter overfore dager dokumenter.")
                        dokumentService.slettDokumeter(
                            urlBolks = cleanupMelding.meldingV1.dokumentUrls,
                            aktørId = AktørId(cleanupMelding.meldingV1.søker.aktørId),
                            correlationId = CorrelationId(entry.metadata.correlationId)
                        )
                        logger.info("Dokumenter slettet.")
                        logger.info("Videresender journalført overføre dager melding")
                        cleanupMelding.journalførtMelding.serialiserTilData()
                    }
                }
                .to(tilJournalfort.name, tilJournalfort.produced)
            return builder.build()
        }
    }
    internal fun stop() = stream.stop(becauseOfError = false)
}
