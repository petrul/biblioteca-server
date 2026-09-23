package ro.editii.scriptorium.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import org.junit.jupiter.api.Test
import ro.editii.scriptorium.dto.AuthorDto
import ro.editii.scriptorium.dto.OpusRemovedDto
import ro.editii.scriptorium.dto.TeiDivDto
import ro.editii.scriptorium.dto.UserLoggedInDto

import java.nio.file.Files
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Keeps src/main/resources/static/asyncapi.yml honest against what this
 * service actually publishes - the async-side equivalent of WebITest's
 * exportedOpenApiYamlIsValidAndInternallyConsistent(), which does the
 * same job for the live-generated REST spec. asyncapi.yml is
 * hand-maintained (nothing auto-generates it from these DTOs the way
 * springdoc generates the REST spec from annotations), so nothing else
 * catches a DTO field getting added/renamed/retyped without the spec
 * being updated to match - this test is that catch.
 *
 * Serializes real DTO instances with the exact same ObjectMapper
 * KafkaProducer.sendAsJson uses (tools.jackson's JsonMapper), so a
 * serialization-shape mismatch (e.g. a field Jackson renames or omits)
 * would be caught here too, not just a schema/field-name typo.
 */
class AsyncApiContractTest {

    // asyncapi.yml is YAML; networknt's schema factory resolves $ref by
    // fetching a URI + JSON pointer fragment and expects JSON at that
    // URI. Converting to a temp JSON file once and pointing the factory
    // at *that* lets $ref: '#/components/schemas/X' resolve normally
    // against the whole document, including the couple of
    // self-referential/cross-referential ones (TeiDivDto.children ->
    // TeiDivDto, TeiDivDto.author -> AuthorDto) - rather than hand-rolling
    // ref resolution for a document this shape.
    private static File asyncApiAsJsonFile() {
        final yamlMapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
        final JsonNode root = yamlMapper.readTree(
                AsyncApiContractTest.class.getResourceAsStream('/static/asyncapi.yml'))
        final tmp = Files.createTempFile('asyncapi', '.json').toFile()
        tmp.deleteOnExit()
        new ObjectMapper().writeValue(tmp, root)
        return tmp
    }

    private static JsonSchema schemaFor(String schemaName) {
        final file = asyncApiAsJsonFile()
        final factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        return factory.getSchema(URI.create("file://${file.absolutePath}#/components/schemas/${schemaName}"))
    }

    // The exact serialization KafkaTextbaseEventsPublisher.signalNewOpusImported/
    // signalOpusReimported/signalUserLoggedIn actually use (KafkaProducer.sendAsJson).
    private static JsonNode serializeLikeKafkaProducerDoes(Object dto) {
        final mapper = new tools.jackson.databind.json.JsonMapper()
        final String json = mapper.writeValueAsString(dto)
        return new ObjectMapper().readTree(json)
    }

    @Test
    void aRealTeiDivDtoValidatesAgainstAsyncapiYamlsSchema() {
        final author = AuthorDto.builder()
                .strId('creanga')
                .lastName('Creangă')
                .firstName('Ion')
                .displayName('Ion Creangă')
                .build()
        // Not teiDivDtoBuilder(): it's dead code in production (the real
        // factory, TeiDivDto.fromTeiDiv, assigns fields directly via a
        // double-brace initializer) and turns out to be genuinely broken -
        // every field its custom constructor sets (path, urlFragment, url,
        // size, wordSize, parent) silently stays null on the built object.
        // Plain field assignment here instead, matching what fromTeiDiv
        // actually does and what real TeiDivDto instances actually get.
        final opus = new TeiDivDto()
        opus.id = 42L
        opus.path = 'creanga/povesti'
        opus.urlFragment = 'povesti'
        opus.head = 'Povești'
        opus.url = 'https://textbase.scriptorium.ro/creanga/povesti'
        opus.depth = 1
        opus.size = 12345
        opus.wordSize = 2345
        opus.children = new TeiDivDto[0]
        opus.author = author
        opus.leaf = false
        opus.opus = true

        final node = serializeLikeKafkaProducerDoes(opus)
        final errors = schemaFor('TeiDivDto').validate(node)
        assertTrue(errors.isEmpty(), "TeiDivDto no longer matches asyncapi.yml's schema: ${errors}")
    }

    @Test
    void aRealUserLoggedInDtoValidatesAgainstAsyncapiYamlsSchema() {
        final event = UserLoggedInDto.builder()
                .userId(7L)
                .username('someone@example.com')
                .provider('google')
                .loginAt(Instant.now())
                .build()

        final node = serializeLikeKafkaProducerDoes(event)
        final errors = schemaFor('UserLoggedInDto').validate(node)
        assertTrue(errors.isEmpty(), "UserLoggedInDto no longer matches asyncapi.yml's schema: ${errors}")
    }

    // The three real topic names KafkaProps.java exposes must match the
    // channel addresses documented in asyncapi.yml exactly - a rename on
    // either side without the other is exactly the kind of drift this
    // whole test class exists to catch.
    @Test
    void kafkaPropsTopicNamesMatchAsyncapiYamlsChannelAddresses() {
        final root = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readTree(getClass().getResourceAsStream('/static/asyncapi.yml'))
        final channels = root.path('channels')
        final props = new KafkaProps()

        assert props.newOpusImportedTopicName.startsWith('biblioteca_')
        assert props.opusReimportedTopicName.startsWith('biblioteca_')
        assert props.opusRemovedTopicName.startsWith('biblioteca_')
        assert props.loginTopicName.startsWith('biblioteca_')

        assert channels.path('newOpusImportedTopic').path('address').asText() == props.newOpusImportedTopicName
        assert channels.path('opusReimportedTopic').path('address').asText() == props.opusReimportedTopicName
        assert channels.path('opusRemovedTopic').path('address').asText() == props.opusRemovedTopicName
        assert channels.path('loginTopic').path('address').asText() == props.loginTopicName
    }
}
