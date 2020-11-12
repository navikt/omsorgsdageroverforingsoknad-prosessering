package no.nav.helse.prosessering.v1.deleOmsorgsdager

import no.nav.helse.aktoer.AktørId
import no.nav.helse.prosessering.v1.overforeDager.Arbeidssituasjon
import no.nav.helse.prosessering.v1.overforeDager.PreprossesertSøker
import java.net.URI
import java.time.ZonedDateTime

data class PreprosessertDeleOmsorgsdagerV1(
    val soknadId: String,
    val mottatt: ZonedDateTime,
    val søker: PreprossesertSøker,
    val dokumentUrls: List<List<URI>>,
    val språk: String?,
    val id: String,
    val harForståttRettigheterOgPlikter: Boolean,
    val harBekreftetOpplysninger: Boolean,
    val barn: List<BarnUtvidet>,
    val borINorge: Boolean,
    val arbeiderINorge: Boolean,
    val arbeidssituasjon: List<Arbeidssituasjon>,
    val antallDagerBruktIÅr: Int,
    val mottakerType: Mottaker,
    val mottakerFnr: String,
    val mottakerNavn: String,
    val antallDagerSomSkalOverføres: Int
    ) {
    internal constructor(
        melding: MeldingDeleOmsorgsdagerV1,
        søkerAktørId: AktørId,
        dokumentUrls: List<List<URI>>
    ) : this(
        språk = melding.språk,
        soknadId = melding.søknadId,
        søker = PreprossesertSøker(melding.søker, søkerAktørId),
        dokumentUrls = dokumentUrls,
        mottatt = melding.mottatt,
        id = melding.id,
        harForståttRettigheterOgPlikter = melding.harForståttRettigheterOgPlikter,
        harBekreftetOpplysninger = melding.harBekreftetOpplysninger,
        barn = melding.barn,
        borINorge = melding.borINorge,
        arbeiderINorge = melding.arbeiderINorge,
        arbeidssituasjon = melding.arbeidssituasjon,
        antallDagerBruktIÅr = melding.antallDagerBruktIÅr,
        mottakerType = melding.mottakerType,
        mottakerFnr = melding.mottakerFnr,
        mottakerNavn = melding.mottakerNavn,
        antallDagerSomSkalOverføres = melding.antallDagerSomSkalOverføres
    )
}


