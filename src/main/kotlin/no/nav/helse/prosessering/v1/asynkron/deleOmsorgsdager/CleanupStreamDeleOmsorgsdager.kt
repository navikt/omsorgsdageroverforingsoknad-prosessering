package no.nav.helse.prosessering.v1.asynkron.deleOmsorgsdager

import no.nav.helse.CorrelationId
import no.nav.helse.aktoer.AktørId
import no.nav.helse.dokument.DokumentService
import no.nav.helse.kafka.KafkaConfig
import no.nav.helse.kafka.ManagedKafkaStreams
import no.nav.helse.kafka.ManagedStreamHealthy
import no.nav.helse.kafka.ManagedStreamReady
import no.nav.helse.prosessering.v1.asynkron.*
import no.nav.helse.prosessering.v1.deleOmsorgsdager.BarnUtvidet
import no.nav.helse.prosessering.v1.deleOmsorgsdager.Mottaker
import no.nav.k9.rapid.behov.Behovssekvens
import no.nav.k9.rapid.behov.OverføreOmsorgsdagerBehov
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.slf4j.LoggerFactory


internal class CleanupStreamDeleOmsorgsdager(
    kafkaConfig: KafkaConfig,
    dokumentService: DokumentService
) {
    private val stream = ManagedKafkaStreams(
        name = NAME,
        properties = kafkaConfig.stream(NAME),
        topology = topology(dokumentService),
        unreadyAfterStreamStoppedIn = kafkaConfig.unreadyAfterStreamStoppedIn
    )

    internal val ready = ManagedStreamReady(stream)
    internal val healthy = ManagedStreamHealthy(stream)

    private companion object {
        private const val NAME = "CleanupV1DeleOmsorgsdager"
        private val logger = LoggerFactory.getLogger("no.nav.$NAME.topology")

        private fun topology(dokumentService: DokumentService): Topology {
            val builder = StreamsBuilder()
            val fraCleanup = Topics.CLEANUP_DELE_OMSORGSDAGER
            val tilK9Rapid= Topics.K9_RAPID_V2

            builder
                .stream(fraCleanup.name, fraCleanup.consumed)
                .filter { _, entry -> 1 == entry.metadata.version }
                .selectKey{ key, value ->
                    value.deserialiserTilCleanupDeleOmsorgsdager().meldingV1.id
                }
                .mapValues { soknadId, entry ->
                    process(NAME, soknadId, entry) {
                        val cleanupMelding = entry.deserialiserTilCleanupDeleOmsorgsdager()
                        logger.info(formaterStatuslogging(cleanupMelding.meldingV1.soknadId, "kjører cleanup"))


                        logger.trace("Sletter dele omsorgsdager dokumenter.")
                        dokumentService.slettDokumeter(
                            urlBolks = cleanupMelding.meldingV1.dokumentUrls,
                            aktørId = AktørId(cleanupMelding.meldingV1.søker.aktørId),
                            correlationId = CorrelationId(entry.metadata.correlationId)
                        )
                        logger.trace("Dokumenter slettet.")

                        logger.trace("Mapper om til Behovssekvens")
                        val behovssekvens: Behovssekvens = cleanupMelding.tilK9Behovssekvens()
                        val (id, overføring) = behovssekvens.keyValue

                        logger.info(formaterStatuslogging(cleanupMelding.meldingV1.soknadId, "har behovssekvensID $id og sendes videre til topic ${tilK9Rapid.name} "))

                        Data(overføring)
                    }
                }
                .to(tilK9Rapid.name, tilK9Rapid.produced)
            return builder.build()
        }
    }
    internal fun stop() = stream.stop(becauseOfError = false)
}

internal fun CleanupDeleOmsorgsdager.tilK9Behovssekvens(): Behovssekvens {
    val correlationId = this.metadata.correlationId
    val journalPostIdListe = listOf(this.journalpostId)
    val melding = this.meldingV1

    val overførerFra: OverføreOmsorgsdagerBehov.OverførerFra = OverføreOmsorgsdagerBehov.OverførerFra(
        identitetsnummer = melding.søker.fødselsnummer,
        jobberINorge = melding.arbeiderINorge
    )

    val overførerTil: OverføreOmsorgsdagerBehov.OverførerTil = OverføreOmsorgsdagerBehov.OverførerTil(
        identitetsnummer = melding.mottakerFnr,
        relasjon = melding.mottakerType.tilK9Relasjon(),
        harBoddSammenMinstEttÅr = melding.mottakerType.let {
            if(it == Mottaker.SAMBOER) true else null
        }
    )

    val omsorgsdagerTattUtIÅr = melding.antallDagerBruktIÅr
    val omsorgsdagerÅOverføre = melding.antallDagerSomSkalOverføres
    val kilde = OverføreOmsorgsdagerBehov.Kilde.Digital

    val listeOverBarn = melding.barn.tilK9BarnListe()

    val behovssekvens: Behovssekvens = Behovssekvens(
        id = melding.id,
        correlationId = correlationId,
        behov = *arrayOf(
            OverføreOmsorgsdagerBehov(
                fra = overførerFra,
                til = overførerTil,
                omsorgsdagerTattUtIÅr = omsorgsdagerTattUtIÅr,
                omsorgsdagerÅOverføre = omsorgsdagerÅOverføre,
                barn = listeOverBarn,
                kilde = kilde,
                journalpostIder = journalPostIdListe
            )
        )
    )

    return behovssekvens
}

internal fun List<BarnUtvidet>.tilK9BarnListe() : List<OverføreOmsorgsdagerBehov.Barn>{
    var listeOverBarn: MutableList<OverføreOmsorgsdagerBehov.Barn> = mutableListOf()
    forEach {
        listeOverBarn.add(
            it.tilK9Barn()
        )
    }

    return listeOverBarn
}
