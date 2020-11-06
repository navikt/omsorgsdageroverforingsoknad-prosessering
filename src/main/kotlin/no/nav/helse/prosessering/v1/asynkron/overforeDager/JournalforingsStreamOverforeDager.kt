package no.nav.helse.prosessering.v1.asynkron.overforeDager

import no.nav.helse.CorrelationId
import no.nav.helse.aktoer.AktørId
import no.nav.helse.erEtter
import no.nav.helse.joark.JoarkGateway
import no.nav.helse.joark.Navn
import no.nav.helse.kafka.KafkaConfig
import no.nav.helse.kafka.ManagedKafkaStreams
import no.nav.helse.kafka.ManagedStreamHealthy
import no.nav.helse.kafka.ManagedStreamReady
import no.nav.helse.prosessering.v1.asynkron.*
import no.nav.helse.prosessering.v1.overforeDager.Fosterbarn
import no.nav.helse.prosessering.v1.overforeDager.PreprossesertOverforeDagerV1
import no.nav.helse.prosessering.v1.overforeDager.PreprossesertSøker
import no.nav.k9.søknad.felles.Barn
import no.nav.k9.søknad.felles.NorskIdentitetsnummer
import no.nav.k9.søknad.felles.Søker
import no.nav.k9.søknad.felles.SøknadId
import no.nav.k9.søknad.omsorgspenger.overføring.Mottaker
import no.nav.k9.søknad.omsorgspenger.overføring.OmsorgspengerOverføringSøknad
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.slf4j.LoggerFactory
import java.time.ZonedDateTime

internal class JournalforingsStreamOverforeDager(
    joarkGateway: JoarkGateway,
    kafkaConfig: KafkaConfig,
    datoMottattEtter: ZonedDateTime
) {

    private val stream = ManagedKafkaStreams(
        name = NAME,
        properties = kafkaConfig.stream(NAME),
        topology = topology(joarkGateway, datoMottattEtter),
        unreadyAfterStreamStoppedIn = kafkaConfig.unreadyAfterStreamStoppedIn
    )

    internal val ready = ManagedStreamReady(stream)
    internal val healthy = ManagedStreamHealthy(stream)

    private companion object {
        private const val NAME = "JournalforingV1OverforeDager"
        private val logger = LoggerFactory.getLogger("no.nav.$NAME.topology")

        private val erJournalført = mutableListOf<String>()

        private fun topology(joarkGateway: JoarkGateway, gittDato: ZonedDateTime): Topology {
            val builder = StreamsBuilder()
            val fraPreprossesertV1 = Topics.PREPROSSESERT_OVERFOREDAGER
            val tilCleanup = Topics.CLEANUP_OVERFOREDAGER

            val mapValues = builder
                .stream(fraPreprossesertV1.name, fraPreprossesertV1.consumed)
                .filter { _, entry -> entry.deserialiserTilPreprossesertOverforeDagerV1().mottatt.erEtter(gittDato) }
                .filter { _, entry -> 2 == entry.metadata.version }
                .mapValues { soknadId, entry ->
                    process(NAME, soknadId, entry) {
                        val preprosessertMelding =
                            entry.deserialiserTilPreprossesertOverforeDagerV1()

                        val dokumenter = preprosessertMelding.dokumentUrls
                        logger.info("Journalfører overføre dager dokumenter: {}", dokumenter)
                        val journaPostId = joarkGateway.journalførOverforeDager(
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
                        logger.info("Dokumenter til overføre dager journalført med ID = ${journaPostId.journalpostId}.")

                        val journalfort = JournalfortOverforeDager(
                            journalpostId = journaPostId.journalpostId,
                                søknad = preprosessertMelding.tilK9OmsorgspengerOverføringSøknad()
                            )

                        erJournalført.add(entry.metadata.correlationId)

                        CleanupOverforeDager(
                            metadata = entry.metadata,
                            meldingV1 = preprosessertMelding,
                            journalførtMelding = journalfort
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

private fun PreprossesertOverforeDagerV1.tilK9OmsorgspengerOverføringSøknad(): OmsorgspengerOverføringSøknad {
    val builder = OmsorgspengerOverføringSøknad.builder()
        .søknadId(SøknadId.of(soknadId))
        .mottattDato(mottatt)
        .søker(søker.tilK9Søker())
        .mottaker(fnrMottaker.tilK9Mottaker())

    fosterbarn?.let { builder.barn(it.tilK9Barn()) }

    return builder.build()
}

private fun List<Fosterbarn>.tilK9Barn(): List<Barn> {
    return map {
        Barn.builder()
            .norskIdentitetsnummer(NorskIdentitetsnummer.of(it.fødselsnummer))
            .build()
    }
}

private fun PreprossesertSøker.tilK9Søker() = Søker.builder()
    .norskIdentitetsnummer(NorskIdentitetsnummer.of(fødselsnummer))
    .build()

private fun String.tilK9Mottaker() = Mottaker.builder()
    .norskIdentitetsnummer(NorskIdentitetsnummer.of(this))
    .build()
