package ro.editii.scriptorium.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import tools.jackson.databind.json.JsonMapper
import org.junit.jupiter.api.Test
import ro.editii.scriptorium.dto.AuthorDto
import ro.editii.scriptorium.dto.OpusRemovedDto
import ro.editii.scriptorium.dto.TeiDivDto
import ro.editii.scriptorium.dto.UserLoggedInDto

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

    // asyncapi.yml is YAML; the registry wants JSON, so convert once (~ the
    // 1.x flow's temp file, in memory). Registering the whole document as one
    // resource lets $ref: '#/components/schemas/X' resolve normally against
    // the whole document, including the couple of self-referential/
    // cross-referential ones (TeiDivDto.children -> TeiDivDto, TeiDivDto.author
    // -> AuthorDto) - rather than hand-rolling ref resolution for a document
    // this shape.
    private static final String ASYNCAPI_BASE = 'https://biblioteca.invalid/asyncapi.json'

    private static String asyncApiAsJson() {
        final yamlMapper = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
        final JsonNode root = yamlMapper.readTree(
                AsyncApiContractTest.class.getResourceAsStream('/static/asyncapi.yml'))
        return new ObjectMapper().writeValueAsString(root)
    }

    private static Schema schemaFor(String schemaName) {
        // json-schema-validator 3.x (the version the MCP server stack brings):
        // schemas resolve through a registry's resource loader, here an
        // in-memory document registered under ASYNCAPI_BASE.
        final loader = new com.networknt.schema.resource.SchemaLoader(
                new com.networknt.schema.resource.MapResourceLoader([(ASYNCAPI_BASE): asyncApiAsJson()]))
        final registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemaLoader(loader)
        }
        return registry.getSchema(SchemaLocation.of("${ASYNCAPI_BASE}#/components/schemas/${schemaName}"))
    }

    // The exact serialization KafkaTextbaseEventsPublisher.signalNewOpusImported/
    // signalOpusReimported/signalUserLoggedIn actually use (KafkaProducer.sendAsJson).
    private static tools.jackson.databind.JsonNode serializeLikeKafkaProducerDoes(Object dto) {
        final mapper = new JsonMapper()
        final String json = mapper.writeValueAsString(dto)
        return mapper.readTree(json)
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
        assert channels.path('humanEvents').path('address').asText() == props.humanEventsTopicName
        assert props.humanEventsTopicName == 'biblioteca_events'
    }
}
