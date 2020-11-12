package no.nav.helse.prosessering.v1.deleOmsorgsdager

import com.fasterxml.jackson.annotation.JsonProperty
import no.nav.helse.prosessering.v1.overforeDager.Arbeidssituasjon
import no.nav.helse.prosessering.v1.overforeDager.Søker
import no.nav.k9.rapid.behov.OverføreOmsorgsdagerBehov
import java.time.LocalDate
import java.time.ZonedDateTime

data class MeldingDeleOmsorgsdagerV1(
    val søknadId: String,
    val mottatt: ZonedDateTime,
    val søker: Søker,
    val språk: String,
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
)

enum class Mottaker(val utskriftsvennlig: String) {
    @JsonProperty("ektefelle") EKTEFELLE("Ektefelle"),
    @JsonProperty("samboer") SAMBOER("Samboer");

    fun tilK9Relasjon(): OverføreOmsorgsdagerBehov.Relasjon {
        return when(this){
            EKTEFELLE -> OverføreOmsorgsdagerBehov.Relasjon.NåværendeEktefelle
            SAMBOER -> OverføreOmsorgsdagerBehov.Relasjon.NåværendeSamboer
        }
    }
}

data class BarnUtvidet(
    var identitetsnummer: String,
    val aktørId: String?,
    val fødselsdato: LocalDate,
    val navn: String,
    val aleneOmOmsorgen: Boolean,
    val utvidetRett: Boolean
) {
    fun tilK9Barn() : OverføreOmsorgsdagerBehov.Barn{
        return OverføreOmsorgsdagerBehov.Barn(
            identitetsnummer = this.identitetsnummer,
            fødselsdato = this.fødselsdato,
            aleneOmOmsorgen = this.aleneOmOmsorgen,
            utvidetRett = this.utvidetRett
        )
    }
}
