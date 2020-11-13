package no.nav.helse.prosessering.v1.deleOmsorgsdager

import io.prometheus.client.Counter
import io.prometheus.client.Histogram

private val antallDeleOmsorgsdagerHistogram = Histogram.build()
    .buckets(1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0,9.0,10.0,11.0,12.0,13.0,14.0,15.0,16.0,17.0,18.0,19.0,20.0)
    .name("antall_dele_omsorgsdager_histogram")
    .help("Antall omsorgsdager man deler")
    .register()

private val antallBruktedagerHistogram = Histogram.build()
    .buckets(1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0,9.0,10.0,11.0,12.0,13.0,14.0,15.0,16.0,17.0,18.0,19.0,20.0)
    .name("antall_brukte_dager_histogram")
    .help("Antall omsorgsdager man allerede har brukt")
    .register()

private val jaNeiDeleDagerCounter = Counter.build()
    .name("ja_nei_dele_dager_counter")
    .help("Teller for svar på ja/nei spørsmål for dele-dager")
    .labelNames("spm", "svar")
    .register()

internal fun PreprosessertDeleOmsorgsdagerV1.reportMetrics() {
    antallDeleOmsorgsdagerHistogram.observe(antallDagerSomSkalOverføres.toDouble())
    antallBruktedagerHistogram.observe(antallDagerBruktIÅr.toDouble())

    jaNeiDeleDagerCounter.labels("utvidetRett", barn.harNoenUtvidetRett().tilJaEllerNei()).inc()
    jaNeiDeleDagerCounter.labels("fordelingSamboerEllerEktefelle", mottakerType.name).inc()
    jaNeiDeleDagerCounter.labels("brukteDager", (antallDagerBruktIÅr > 0).tilJaEllerNei()).inc()
}

private fun Boolean.tilJaEllerNei(): String = if (this) "Ja" else "Nei"

private fun List<BarnUtvidet>.harNoenUtvidetRett(): Boolean{
    forEach {
        if (it.utvidetRett) return true
    }
    return false
}