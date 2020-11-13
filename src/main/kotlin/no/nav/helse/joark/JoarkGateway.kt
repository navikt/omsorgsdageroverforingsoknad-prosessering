package no.nav.helse.joark

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.fuel.coroutines.awaitStringResponseResult
import com.github.kittinunf.fuel.httpPost
import io.ktor.http.*
import no.nav.helse.CorrelationId
import no.nav.helse.HttpError
import no.nav.helse.aktoer.AktørId
import no.nav.helse.dusseldorf.ktor.client.buildURL
import no.nav.helse.dusseldorf.ktor.health.HealthCheck
import no.nav.helse.dusseldorf.ktor.health.Healthy
import no.nav.helse.dusseldorf.ktor.health.Result
import no.nav.helse.dusseldorf.ktor.health.UnHealthy
import no.nav.helse.dusseldorf.ktor.metrics.Operation
import no.nav.helse.dusseldorf.oauth2.client.AccessTokenClient
import no.nav.helse.dusseldorf.oauth2.client.CachedAccessTokenClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.ZonedDateTime

class JoarkGateway(
    baseUrl: URI,
    private val accessTokenClient: AccessTokenClient,
    private val journalforeScopes: Set<String>
) : HealthCheck {
    private companion object {
        private const val JOURNALFORING_OPERATION = "journalforing"
        private val logger: Logger = LoggerFactory.getLogger(JoarkGateway::class.java)
    }

    private val completeUrlOverforeDager = Url.buildURL(
        baseUrl = baseUrl,
        pathParts = listOf("v1", "omsorgsdageroverforing", "journalforing")
    ).toString()


    private val completeUrlDeleDager = Url.buildURL(
        baseUrl = baseUrl,
        pathParts = listOf("v1", "omsorgsdagerdeling", "journalforing")
    ).toString()

    private val objectMapper = configuredObjectMapper()
    private val cachedAccessTokenClient = CachedAccessTokenClient(accessTokenClient)


    override suspend fun check(): Result {
        return try {
            accessTokenClient.getAccessToken(journalforeScopes)
            Healthy("JoarkGateway", "Henting av access token for journalføring OK.")
        } catch (cause: Throwable) {
            logger.error("Feil ved henting av access token for journalføring", cause)
            UnHealthy("JoarkGateway", "Henting av access token for journalføring feilet.")
        }
    }

    suspend fun journalførOverforeDager(
        aktørId: AktørId,
        norskIdent: String,
        mottatt: ZonedDateTime,
        dokumenter: List<List<URI>>,
        correlationId: CorrelationId,
        navn: Navn
    ): JournalPostId {

        val authorizationHeader = cachedAccessTokenClient.getAccessToken(journalforeScopes).asAuthoriationHeader()

        val joarkRequest = JoarkRequest(
            aktoerId = aktørId.id,
            norskIdent = norskIdent,
            mottatt = mottatt,
            dokumenter = dokumenter,
            søkerNavn = navn
        )

        val body = objectMapper.writeValueAsBytes(joarkRequest)
        val contentStream = { ByteArrayInputStream(body) }

        val httpRequest = completeUrlOverforeDager
            .httpPost()
            .body(contentStream)
            .header(
                HttpHeaders.XCorrelationId to correlationId.value,
                HttpHeaders.Authorization to authorizationHeader,
                HttpHeaders.ContentType to "application/json",
                HttpHeaders.Accept to "application/json"
            )

        val (request, response, result) = Operation.monitored(
            app = "omsorgsdageroverforingsoknad-prosessering",
            operation = JOURNALFORING_OPERATION,
            resultResolver = { 201 == it.second.statusCode }
        ) { httpRequest.awaitStringResponseResult() }

        return result.fold(
            { success -> objectMapper.readValue(success) },
            { error ->
                logger.error("Error response = '${error.response.body().asString("text/plain")}' fra '${request.url}'")
                logger.error(error.toString())
                throw HttpError(response.statusCode, "Feil ved journalføring.")
            }
        )
    }


    suspend fun journalførDeleDager(
        aktørId: AktørId,
        norskIdent: String,
        mottatt: ZonedDateTime,
        dokumenter: List<List<URI>>,
        correlationId: CorrelationId,
        navn: Navn
    ): JournalPostId {

        val authorizationHeader = cachedAccessTokenClient.getAccessToken(journalforeScopes).asAuthoriationHeader()

        val joarkRequest = JoarkRequest(
            aktoerId = aktørId.id,
            norskIdent = norskIdent,
            mottatt = mottatt,
            dokumenter = dokumenter,
            søkerNavn = navn
        )

        val body = objectMapper.writeValueAsBytes(joarkRequest)
        val contentStream = { ByteArrayInputStream(body) }

        val httpRequest = completeUrlDeleDager
            .httpPost()
            .body(contentStream)
            .header(
                HttpHeaders.XCorrelationId to correlationId.value,
                HttpHeaders.Authorization to authorizationHeader,
                HttpHeaders.ContentType to "application/json",
                HttpHeaders.Accept to "application/json"
            )

        val (request, response, result) = Operation.monitored(
            app = "omsorgsdageroverforingsoknad-prosessering",
            operation = JOURNALFORING_OPERATION,
            resultResolver = { 201 == it.second.statusCode }
        ) { httpRequest.awaitStringResponseResult() }

        return result.fold(
            { success -> objectMapper.readValue(success) },
            { error ->
                logger.error("Error response = '${error.response.body().asString("text/plain")}' fra '${request.url}'")
                logger.error(error.toString())
                throw HttpError(response.statusCode, "Feil ved journalføring.")
            }
        )
    }

    private fun configuredObjectMapper(): ObjectMapper {
        val objectMapper = jacksonObjectMapper()
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        objectMapper.registerModule(JavaTimeModule())
        return objectMapper
    }
}

private data class JoarkRequest(
    @JsonProperty("aktoer_id") val aktoerId: String,
    @JsonProperty("norsk_ident") val norskIdent: String,
    @JsonProperty("soker_navn") val søkerNavn: Navn,
    val mottatt: ZonedDateTime,
    val dokumenter: List<List<URI>>
)

data class Navn(
    val fornavn: String,
    val mellomnavn: String? = null,
    val etternavn: String
)

data class JournalPostId(@JsonProperty("journal_post_id") val journalpostId: String)