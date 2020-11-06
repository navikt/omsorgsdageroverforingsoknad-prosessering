package no.nav.helse

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.Application
import io.ktor.application.ApplicationStopping
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.jackson.jackson
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.client.hotspot.DefaultExports
import no.nav.helse.aktoer.AktoerGateway
import no.nav.helse.auth.AccessTokenClientResolver
import no.nav.helse.dokument.DokumentGateway
import no.nav.helse.dokument.DokumentService
import no.nav.helse.dusseldorf.ktor.auth.clients
import no.nav.helse.dusseldorf.ktor.client.HttpRequestHealthCheck
import no.nav.helse.dusseldorf.ktor.client.HttpRequestHealthConfig
import no.nav.helse.dusseldorf.ktor.client.buildURL
import no.nav.helse.dusseldorf.ktor.core.Paths
import no.nav.helse.dusseldorf.ktor.core.logProxyProperties
import no.nav.helse.dusseldorf.ktor.health.HealthRoute
import no.nav.helse.dusseldorf.ktor.health.HealthService
import no.nav.helse.dusseldorf.ktor.jackson.dusseldorfConfigured
import no.nav.helse.dusseldorf.ktor.metrics.MetricsRoute
import no.nav.helse.joark.JoarkGateway
import no.nav.helse.prosessering.v1.PdfV1Generator
import no.nav.helse.prosessering.v1.PreprosseseringV1Service
import no.nav.helse.prosessering.v1.asynkron.AsynkronProsesseringV1Service
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.ZonedDateTime

private val logger: Logger = LoggerFactory.getLogger("nav.OmsorgsdageroverforingsoknadProsessering")

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@KtorExperimentalAPI
fun Application.omsorgsdageroverforingsoknadProsessering() {
    logProxyProperties()
    DefaultExports.initialize()

    install(ContentNegotiation) {
        jackson {
            omsorgsdageroverførningKonfigurertMapper()
        }
    }

    val configuration = Configuration(environment.config)

    val accessTokenClientResolver = AccessTokenClientResolver(environment.config.clients())

    val aktoerGateway = AktoerGateway(
        baseUrl = configuration.getAktoerRegisterBaseUrl(),
        accessTokenClient = accessTokenClientResolver.aktoerRegisterAccessTokenClient()
    )

    val dokumentGateway = DokumentGateway(
        baseUrl = configuration.getK9DokumentBaseUrl(),
        accessTokenClient = accessTokenClientResolver.dokumentAccessTokenClient(),
        lagreDokumentScopes = configuration.getLagreDokumentScopes(),
        sletteDokumentScopes = configuration.getSletteDokumentScopes()
    )
    val dokumentService = DokumentService(dokumentGateway)

    val preprosseseringV1Service = PreprosseseringV1Service(
        pdfV1Generator = PdfV1Generator(),
        dokumentService = dokumentService
    )
    val joarkGateway = JoarkGateway(
        baseUrl = configuration.getk9JoarkBaseUrl(),
        accessTokenClient = accessTokenClientResolver.joarkAccessTokenClient(),
        journalforeScopes = configuration.getJournalforeScopes()
    )

    val asynkronProsesseringV1Service = AsynkronProsesseringV1Service(
        kafkaConfig = configuration.getKafkaConfig(),
        preprosseseringV1Service = preprosseseringV1Service,
        joarkGateway = joarkGateway,
        dokumentService = dokumentService,
        datoMottattEtter = configuration.soknadDatoMottattEtter()
    )

    environment.monitor.subscribe(ApplicationStopping) {
        logger.info("Stopper AsynkronProsesseringV1Service.")
        asynkronProsesseringV1Service.stop()
        logger.info("AsynkronProsesseringV1Service Stoppet.")
    }

    install(Routing) {
        MetricsRoute()
        HealthRoute(
            path = Paths.DEFAULT_ALIVE_PATH,
            healthService = HealthService(
                healthChecks = asynkronProsesseringV1Service.isReadyChecks()
            )
        )
        get(Paths.DEFAULT_READY_PATH) {
            call.respondText("READY")
        }
        HealthRoute(
            healthService = HealthService(
                healthChecks = mutableSetOf(
                    dokumentGateway,
                    joarkGateway,
                    aktoerGateway,
                    HttpRequestHealthCheck(
                        mapOf(
                            Url.healthURL(configuration.getK9DokumentBaseUrl()) to HttpRequestHealthConfig(
                                expectedStatus = HttpStatusCode.OK
                            ),
                            Url.healthURL(configuration.getk9JoarkBaseUrl()) to HttpRequestHealthConfig(
                                expectedStatus = HttpStatusCode.OK
                            )
                        )
                    )
                ).plus(asynkronProsesseringV1Service.healthChecks()).toSet()
            )
        )
    }
}

fun ZonedDateTime.erEtter(zonedDateTime: ZonedDateTime): Boolean = this.isAfter(zonedDateTime)

fun omsorgsdageroverførningKonfigurertMapper(): ObjectMapper {
    return jacksonObjectMapper().dusseldorfConfigured()
        .setPropertyNamingStrategy(PropertyNamingStrategy.LOWER_CAMEL_CASE)
        .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false)
}

private fun Url.Companion.healthURL(baseUrl: URI) = Url.buildURL(baseUrl = baseUrl, pathParts = listOf("health"))
