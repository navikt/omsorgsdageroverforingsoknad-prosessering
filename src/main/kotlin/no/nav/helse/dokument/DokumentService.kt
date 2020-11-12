package no.nav.helse.dokument

import no.nav.helse.CorrelationId
import no.nav.helse.aktoer.AktørId
import no.nav.helse.prosessering.v1.deleOmsorgsdager.MeldingDeleOmsorgsdagerV1
import no.nav.helse.prosessering.v1.overforeDager.SøknadOverføreDagerV1
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI

private val logger: Logger = LoggerFactory.getLogger("nav.DokumentService")

class DokumentService(
    private val dokumentGateway: DokumentGateway
) {
    private suspend fun lagreDokument(
        dokument: DokumentGateway.Dokument,
        aktørId: AktørId,
        correlationId: CorrelationId
    ) : URI {
        return dokumentGateway.lagreDokmenter(
            dokumenter = setOf(dokument),
            correlationId = correlationId,
            aktørId = aktørId
        ).first()
    }

    internal suspend fun lagreSoknadsOppsummeringPdf(
        pdf : ByteArray,
        aktørId: AktørId,
        correlationId: CorrelationId,
        dokumentbeskrivelse: String
    ) : URI {
        return lagreDokument(
            dokument = DokumentGateway.Dokument(
                content = pdf,
                contentType = "application/pdf",
                title = dokumentbeskrivelse
            ),
            aktørId = aktørId,
            correlationId = correlationId
        )
    }

    internal suspend fun lagreSoknadOverforeDagerMelding(
        melding: SøknadOverføreDagerV1,
        aktørId: AktørId,
        correlationId: CorrelationId
    ) : URI {
        return lagreDokument(
            dokument = DokumentGateway.Dokument(
                content = Søknadsformat.somJsonOverforeDager(melding),
                contentType = "application/json",
                title = "Melding om overføring av dager som JSON"
            ),
            aktørId = aktørId,
            correlationId = correlationId
        )
    }

    internal suspend fun lagreDeleOmsorgsdagerMelding(
        melding: MeldingDeleOmsorgsdagerV1,
        aktørId: AktørId,
        correlationId: CorrelationId
    ) : URI {
        return lagreDokument(
            dokument = DokumentGateway.Dokument(
                content = Søknadsformat.somJsonDeleOmsorgsdager(melding),
                contentType = "application/json",
                title = "Melding om deling av omsorgsdager som JSON"
            ),
            aktørId = aktørId,
            correlationId = correlationId
        )
    }

    internal suspend fun slettDokumeter(
        urlBolks: List<List<URI>>,
        aktørId: AktørId,
        correlationId : CorrelationId
    ) {
        val urls = mutableListOf<URI>()
        urlBolks.forEach { urls.addAll(it) }

        logger.trace("Sletter ${urls.size} dokumenter")
        dokumentGateway.slettDokmenter(
            urls = urls,
            aktørId = aktørId,
            correlationId = correlationId
        )
    }
}

