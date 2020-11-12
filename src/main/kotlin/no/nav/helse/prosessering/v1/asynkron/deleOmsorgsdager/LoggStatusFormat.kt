package no.nav.helse.prosessering.v1.asynkron.deleOmsorgsdager

//Brukes når man logger status i flyten. Formaterer slik at loggen er mer lesbar
internal fun formaterStatuslogging(id: String, melding: String): String {
    return String.format("Melding med søknadID: %1$36s %2$1s", id, melding)
}