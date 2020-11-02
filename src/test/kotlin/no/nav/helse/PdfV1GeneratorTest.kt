package no.nav.helse

import no.nav.helse.prosessering.v1.PdfV1Generator
import no.nav.helse.prosessering.v1.overforeDager.*
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.Test

class PdfV1GeneratorTest {

    private companion object {
        private val generator = PdfV1Generator()
        private val fødselsdato = LocalDate.now()
    }

    private fun gyldigSoknadOverforeDager() = SøknadOverføreDagerV1(
        språk = "nb",
        antallDager = 5,
        harBekreftetOpplysninger = true,
        harForståttRettigheterOgPlikter = true,
        arbeidssituasjon = listOf(Arbeidssituasjon.ARBEIDSTAKER, Arbeidssituasjon.FRILANSER, Arbeidssituasjon.SELVSTENDIGNÆRINGSDRIVENDE),
        søknadId = "Overføre dager",
        medlemskap = Medlemskap(
            harBoddIUtlandetSiste12Mnd = true,
            utenlandsoppholdSiste12Mnd = listOf(
                Utenlandsopphold(
                    LocalDate.of(2020, 1, 2),
                    LocalDate.of(2020, 1, 3),
                    "US", "USA"
                )
            ),
            skalBoIUtlandetNeste12Mnd = true,
            utenlandsoppholdNeste12Mnd = listOf(
                Utenlandsopphold(
                    fraOgMed = LocalDate.of(2020,2,1),
                    tilOgMed = LocalDate.of(2020,2,24),
                    landkode = "US",
                    landnavn = "USA"
                )
            )
        ),
        fnrMottaker = "123456789",
        navnMottaker = "Navn Navnesen Mottaker",
        mottatt = ZonedDateTime.now(),
        søker = Søker(
            aktørId = "123456",
            fornavn = "Ærling",
            mellomnavn = "Øverbø",
            etternavn = "Ånsnes",
            fødselsnummer = "29099012345",
            fødselsdato = fødselsdato
        ),
        fosterbarn = listOf(
            Fosterbarn("29099012345"),
            Fosterbarn("02119970078")
        ),
        stengingsperiode = Stengingsperiode.ETTER_AUGUST_9
    )

    private fun genererOppsummeringsPdfer(writeBytes: Boolean) {
        var id = "1-full-søknad-overfore-dager"
        var pdf = generator.generateSoknadOppsummeringPdfOverforeDager(
            melding = gyldigSoknadOverforeDager()
        )
        if (writeBytes) File(pdfPath(soknadId = id)).writeBytes(pdf)
    }

    private fun pdfPath(soknadId: String) = "${System.getProperty("user.dir")}/generated-pdf-$soknadId.pdf"

    @Test
    fun `generering av oppsummerings-PDF fungerer`() {
        genererOppsummeringsPdfer(false)
    }

    @Test
    //@Ignore
    fun `opprett lesbar oppsummerings-PDF`() {
        genererOppsummeringsPdfer(true)
    }
}
