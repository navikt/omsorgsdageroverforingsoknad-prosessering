package no.nav.helse.prosessering.v1

import no.nav.helse.CorrelationId
import no.nav.helse.aktoer.AktørId
import no.nav.helse.dokument.DokumentService
import no.nav.helse.prosessering.Metadata
import no.nav.helse.prosessering.SoknadId
import no.nav.helse.prosessering.v1.deleOmsorgsdager.MeldingDeleOmsorgsdagerV1
import no.nav.helse.prosessering.v1.deleOmsorgsdager.PreprosessertDeleOmsorgsdagerV1
import no.nav.helse.prosessering.v1.deleOmsorgsdager.reportMetrics
import no.nav.helse.prosessering.v1.overforeDager.PreprossesertOverforeDagerV1
import no.nav.helse.prosessering.v1.overforeDager.SøknadOverføreDagerV1
import no.nav.helse.prosessering.v1.overforeDager.reportMetrics
import org.slf4j.LoggerFactory

internal class PreprosesseringV1Service(
    private val pdfV1Generator: PdfV1Generator,
    private val dokumentService: DokumentService
) {

    private companion object {
        private val logger = LoggerFactory.getLogger(PreprosesseringV1Service::class.java)
    }

    internal suspend fun preprosseserOverforeDager(
        melding: SøknadOverføreDagerV1,
        metadata: Metadata
    ): PreprossesertOverforeDagerV1 {
        val søknadId = SoknadId(melding.søknadId)
        logger.info("Preprosseserer søknad om overføring av omsorgsdager med søknadsId: $søknadId")

        val correlationId = CorrelationId(metadata.correlationId)

        val søkerAktørId = AktørId(melding.søker.aktørId)

        logger.info("Søkerens AktørID = $søkerAktørId")

        logger.info("Genererer Oppsummerings-PDF av søknaden.")
        val soknadOppsummeringPdf = pdfV1Generator.generateSoknadOppsummeringPdfOverforeDager(melding)
        logger.info("Generering av Oppsummerings-PDF OK.")

        logger.info("Mellomlagrer Oppsummerings-PDF.")
        val soknadOppsummeringPdfUrl = dokumentService.lagreSoknadsOppsummeringPdf(
            pdf = soknadOppsummeringPdf,
            correlationId = correlationId,
            aktørId = søkerAktørId,
            dokumentbeskrivelse = "Melding om overføring av omsorgsdager"
        )
        logger.info("Mellomlagring av Oppsummerings-PDF OK")

        logger.info("Mellomlagrer Oppsummerings-JSON")

        val soknadJsonUrl = dokumentService.lagreSoknadOverforeDagerMelding(
            melding = melding,
            aktørId = søkerAktørId,
            correlationId = correlationId
        )
        logger.info("Mellomlagrer Oppsummerings-JSON OK.")

        val komplettDokumentUrls = mutableListOf(
            listOf(
                soknadOppsummeringPdfUrl,
                soknadJsonUrl
            )
        )

        logger.info("Totalt ${komplettDokumentUrls.size} dokumentbolker.")

        val preprossesertMeldingV1OverforeDager =
            PreprossesertOverforeDagerV1(
                melding = melding,
                søkerAktørId = søkerAktørId,
                dokumentUrls = komplettDokumentUrls.toList()
            )

        preprossesertMeldingV1OverforeDager.reportMetrics()
        return preprossesertMeldingV1OverforeDager
    }

    internal suspend fun preprosesserMeldingDeleOmsorgsdager(
        melding: MeldingDeleOmsorgsdagerV1,
        metadata: Metadata
    ): PreprosessertDeleOmsorgsdagerV1 {
        val søknadId = SoknadId(melding.søknadId)
        logger.trace("Preprosseserer $søknadId")

        val correlationId = CorrelationId(metadata.correlationId)
        val søkerAktørId = AktørId(melding.søker.aktørId)
        logger.trace("Søkerens AktørID = $søkerAktørId")

        logger.trace("Genererer Oppsummerings-PDF av meldingen.")
        val søknadOppsummeringPdf = pdfV1Generator.generateOppsummeringPdfDeleOmsorgsdager(melding)
        logger.trace("Generering av Oppsummerings-PDF OK.")

        logger.trace("Mellomlagrer Oppsummerings-PDF.")
        val søknadOppsummeringPdfUrl = dokumentService.lagreSoknadsOppsummeringPdf(
            pdf = søknadOppsummeringPdf,
            correlationId = correlationId,
            aktørId = søkerAktørId,
            dokumentbeskrivelse = "Melding om deling av omsorgsdager"
        )
        logger.trace("Mellomlagring av Oppsummerings-PDF OK")

        logger.trace("Mellomlagrer Oppsummerings-JSON")

        val søknadJsonUrl = dokumentService.lagreDeleOmsorgsdagerMelding(
            melding = melding,
            aktørId = søkerAktørId,
            correlationId = correlationId
        )
        logger.trace("Mellomlagrer Oppsummerings-JSON OK.")

        val komplettDokumentUrls = mutableListOf(
            listOf(søknadOppsummeringPdfUrl, søknadJsonUrl)
        )

        logger.trace("Totalt ${komplettDokumentUrls.size} dokumentbolker.")

        val preprosessertDeleOmsorgsdagerV1 = PreprosessertDeleOmsorgsdagerV1(
                melding = melding,
                søkerAktørId = søkerAktørId,
                dokumentUrls = komplettDokumentUrls.toList()
            )

        preprosessertDeleOmsorgsdagerV1.reportMetrics()
        return preprosessertDeleOmsorgsdagerV1
    }

}
