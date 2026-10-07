package ro.editii.scriptorium.dto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Keeps BookMetadataDto (GET /api/divs/{id}/cover-metadata) honest against
 * schemas/book-metadata.schema.json - the cover renderer's own BookMetadata
 * contract, stored in this project for exactly this - same pattern as
 * AsyncApiContractTest for the Kafka DTOs.
 */
class BookMetadataContractTest {

    private static JsonSchema schema() {
        final factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
        return factory.getSchema(
                BookMetadataContractTest.class.getResourceAsStream('/schemas/book-metadata.schema.json'))
    }

    private static JsonNode asJsonNode(Object dto) {
        return new ObjectMapper().valueToTree(dto)
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
        final JsonNode missingPublisher = new ObjectMapper().createObjectNode()
                .put('title', 'T').put('author', 'A').put('date', '2024')

        final errors = schema().validate(missingPublisher)
        assertTrue(!errors.isEmpty(), 'Expected a missing required "publisher" to fail validation')
    }
}
