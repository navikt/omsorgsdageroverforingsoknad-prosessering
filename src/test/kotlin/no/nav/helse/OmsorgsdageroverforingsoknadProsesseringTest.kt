package no.nav.helse

import com.github.tomakehurst.wiremock.WireMockServer
import com.typesafe.config.ConfigFactory
import io.ktor.config.ApplicationConfig
import io.ktor.config.HoconApplicationConfig
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.stop
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.createTestEnvironment
import io.ktor.server.testing.handleRequest
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import no.nav.common.KafkaEnvironment
import no.nav.helse.dusseldorf.testsupport.wiremock.WireMockBuilder
import no.nav.helse.k9.assertOverføreDagerFormat
import no.nav.helse.prosessering.v1.overforeDager.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals


@KtorExperimentalAPI
class OmsorgsdageroverforingsoknadProsesseringTest {

    @KtorExperimentalAPI
    private companion object {

        private val logger: Logger = LoggerFactory.getLogger(OmsorgsdageroverforingsoknadProsesseringTest::class.java)

        private val wireMockServer: WireMockServer = WireMockBuilder()
            .withNaisStsSupport()
            .withAzureSupport()
            .navnOppslagConfig()
            .build()
            .stubK9DokumentHealth()
            .stubOmsorgspengerJoarkHealth()
            .stubJournalfor()
            .stubLagreDokument()
            .stubSlettDokument()
            .stubAktørRegister("29099012345", "123456")

        private val kafkaEnvironment = KafkaWrapper.bootstrap()
        private val kafkaTestProducerOverforeDager = kafkaEnvironment.meldingOverforeDagersProducer()

        private val journalføringsKonsumerOverforeDager = kafkaEnvironment.journalføringsKonsumerOverforeDager()

        // Se https://github.com/navikt/dusseldorf-ktor#f%C3%B8dselsnummer
        private val gyldigFodselsnummerA = "02119970078"
        private val gyldigFodselsnummerB = "19066672169"
        private val dNummerA = "55125314561"

        private var engine = newEngine(kafkaEnvironment).apply {
            start(wait = true)
        }

        private fun getConfig(kafkaEnvironment: KafkaEnvironment?): ApplicationConfig {
            val fileConfig = ConfigFactory.load()
            val testConfig = ConfigFactory.parseMap(
                TestConfiguration.asMap(
                    wireMockServer = wireMockServer,
                    kafkaEnvironment = kafkaEnvironment
                )
            )
            val mergedConfig = testConfig.withFallback(fileConfig)
            return HoconApplicationConfig(mergedConfig)
        }

        private fun newEngine(kafkaEnvironment: KafkaEnvironment?) = TestApplicationEngine(createTestEnvironment {
            config = getConfig(kafkaEnvironment)
        })

        private fun stopEngine() = engine.stop(5, 60, TimeUnit.SECONDS)

        internal fun restartEngine() {
            stopEngine()
            engine = newEngine(kafkaEnvironment)
            engine.start(wait = true)
        }

        @BeforeClass
        @JvmStatic
        fun buildUp() {
            wireMockServer.stubAktørRegister(gyldigFodselsnummerA, "666666666")
            wireMockServer.stubAktørRegister(gyldigFodselsnummerB, "777777777")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            logger.info("Tearing down")
            wireMockServer.stop()
            journalføringsKonsumerOverforeDager.close()
            kafkaTestProducerOverforeDager.close()
            stopEngine()
            kafkaEnvironment.tearDown()
            logger.info("Tear down complete")
        }
    }

    @Test
    fun `test isready, isalive, health og metrics`() {
        with(engine) {
            handleRequest(HttpMethod.Get, "/isready") {}.apply {
                assertEquals(HttpStatusCode.OK, response.status())
                handleRequest(HttpMethod.Get, "/isalive") {}.apply {
                    assertEquals(HttpStatusCode.OK, response.status())
                    handleRequest(HttpMethod.Get, "/metrics") {}.apply {
                        assertEquals(HttpStatusCode.OK, response.status())
                        handleRequest(HttpMethod.Get, "/health") {}.apply {
                            assertEquals(HttpStatusCode.OK, response.status())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun`Gyldig søknad for overføring av dager blir prosessert av journalføringkonsumer`(){
        val søknad = gyldigMeldingOverforeDager(
            fødselsnummerSoker = gyldigFodselsnummerA,
            sprak = "nb"
        )

        kafkaTestProducerOverforeDager.leggTilMottak(søknad)
        journalføringsKonsumerOverforeDager
            .hentJournalførtMeldingOverforeDager(søknad.søknadId)
            .assertOverføreDagerFormat()
    }

    @Test
    fun `En feilprosessert søknad om overføring av dager vil bli prosessert etter at tjenesten restartes`() {
        val melding = gyldigMeldingOverforeDager(
            fødselsnummerSoker = gyldigFodselsnummerA
        )

        wireMockServer.stubJournalfor(500) // Simulerer feil ved journalføring

        kafkaTestProducerOverforeDager.leggTilMottak(melding)
        ventPaaAtRetryMekanismeIStreamProsessering()
        readyGir200HealthGir503()

        wireMockServer.stubJournalfor(201) // Simulerer journalføring fungerer igjen
        restartEngine()
        journalføringsKonsumerOverforeDager
            .hentJournalførtMeldingOverforeDager(melding.søknadId)
            .assertOverføreDagerFormat()
    }

    private fun readyGir200HealthGir503() {
        with(engine) {
            handleRequest(HttpMethod.Get, "/isready") {}.apply {
                assertEquals(HttpStatusCode.OK, response.status())
                handleRequest(HttpMethod.Get, "/health") {}.apply {
                    assertEquals(HttpStatusCode.ServiceUnavailable, response.status())
                }
            }
        }
    }

    @Test
    fun `Søknad om overføre dager, søker med D-nummer`() {
        val melding = gyldigMeldingOverforeDager(
            fødselsnummerSoker = dNummerA
        )

        kafkaTestProducerOverforeDager.leggTilMottak(melding)
        journalføringsKonsumerOverforeDager
            .hentJournalførtMeldingOverforeDager(melding.søknadId)
            .assertOverføreDagerFormat()
    }


    @Test
    fun `Forvent riktig format på journalført melding`() {
        val melding = gyldigMeldingOverforeDager(
            fødselsnummerSoker = gyldigFodselsnummerA
        )

        kafkaTestProducerOverforeDager.leggTilMottak(melding)
        journalføringsKonsumerOverforeDager
            .hentJournalførtMeldingOverforeDager(melding.søknadId)
            .assertOverføreDagerFormat()
    }

    private fun gyldigMeldingOverforeDager(
        fødselsnummerSoker: String,
        sprak: String = "nb"
    ) : SøknadOverføreDagerV1 = SøknadOverføreDagerV1(
        språk = sprak,
        søknadId = UUID.randomUUID().toString(),
        mottatt = ZonedDateTime.now(),
        søker = Søker(
            aktørId = "123456",
            fødselsnummer = fødselsnummerSoker,
            fødselsdato = LocalDate.now().minusDays(1000),
            etternavn = "Nordmann",
            mellomnavn = "Mellomnavn",
            fornavn = "Ola"
        ),
        arbeidssituasjon = listOf(Arbeidssituasjon.ARBEIDSTAKER),
        harBekreftetOpplysninger = true,
        harForståttRettigheterOgPlikter = true,
        medlemskap = Medlemskap(
            harBoddIUtlandetSiste12Mnd = true,
            skalBoIUtlandetNeste12Mnd = true
        ),
        antallDager = 5,
        fnrMottaker = gyldigFodselsnummerB,
        navnMottaker = "Navn navnesen",
        fosterbarn = listOf(
            Fosterbarn("29099012345"),
            Fosterbarn("02119970078")
        )
    )

    private fun ventPaaAtRetryMekanismeIStreamProsessering() = runBlocking { delay(Duration.ofSeconds(30)) }
}
