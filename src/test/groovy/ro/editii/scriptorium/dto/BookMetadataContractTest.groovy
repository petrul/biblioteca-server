package ro.editii.scriptorium.dto

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Keeps BookMetadataDto (GET /api/divs/{id}/cover-metadata) honest against
 * schemas/book-metadata.schema.json - the cover renderer's own BookMetadata
 * contract, stored in this project for exactly this - same pattern as
 * AsyncApiContractTest for the Kafka DTOs.
 *
 * json-schema-validator 3.x (the version the MCP server stack brings):
 * schemas come from a SchemaRegistry with an explicit default dialect, and
 * validate() returns a List&lt;Error&gt;.
 */
class BookMetadataContractTest {

    private static final ObjectMapper JSON = new ObjectMapper()

    private static Schema schema() {
        final registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7)
        return registry.getSchema(
                BookMetadataContractTest.class.getResourceAsStream('/schemas/book-metadata.schema.json'))
    }

    private static JsonNode asJsonNode(Object dto) {
        return JSON.valueToTree(dto)
    }

    @Test
    void aFullyPopulatedBookMetadataDtoValidatesAgainstTheSchema() {
        final dto = BookMetadataDto.builder()
                .title('Ultima noapte de dragoste, întâia noapte de război')
                .subtitle('')
                .author('Camil Petrescu')
                .editor('')
                .translator('')
                .publisher('Biblioteca')
                .pubPlace('')
                .date('1930')
                .isbn('')
                .series('')
                .volume('')
                .taglineQuote('')
                .genre('')
                .editionNotice('')
                .language('ro')
                .build()

        final errors = schema().validate(asJsonNode(dto))
        assertTrue(errors.isEmpty(), "BookMetadataDto no longer matches book-metadata.schema.json: ${errors}")
    }

    @Test
    void theMinimalRequiredFieldsAloneStillValidate() {
        // What a work with none of the optional TEI header fields actually
        // produces (the common case in this corpus - see the controller's
        // own comment): only the four required fields, the rest simply
        // never set and so never serialized (@JsonInclude NON_NULL).
        final dto = BookMetadataDto.builder()
                .title('Poezii')
                .author('Costache Negri')
                .publisher('Biblioteca')
                .date('')
                .build()

        final errors = schema().validate(asJsonNode(dto))
        assertTrue(errors.isEmpty(), "Minimal BookMetadataDto no longer matches book-metadata.schema.json: ${errors}")
    }

    @Test
    void missingARequiredFieldFailsValidation() {
        // Guards the guard: if this ever passes, the schema file or the
        // validator wiring above is broken, not the DTO.
        final JsonNode missingPublisher = JSON.createObjectNode()
                .put('title', 'T').put('author', 'A').put('date', '2024')

        final errors = schema().validate(missingPublisher)
        assertTrue(!errors.isEmpty(), 'Expected a missing required "publisher" to fail validation')
    }
}
