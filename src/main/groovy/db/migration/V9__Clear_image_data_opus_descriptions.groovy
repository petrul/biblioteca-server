package db.migration

import groovy.transform.CompileStatic
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * Earlier imports built opus descriptions from the opening paragraphs' raw
 * text, which for works opening with an embedded figure was its base64 image
 * data. Cleared here (re-importing the work regenerates a proper
 * description); skipped on a database that has no opus table yet.
 */
@CompileStatic
class V9__Clear_image_data_opus_descriptions extends BaseJavaMigration {

    @Override
    void migrate(Context context) {
        final boolean exists = ['TEI_OPUS', 'tei_opus'].any { String name ->
            context.connection.metaData.getTables(null, null, name, ['TABLE'] as String[]).withCloseable { it.next() }
        }
        if (!exists) return
        context.connection.createStatement().withCloseable { st ->
            st.executeUpdate('''UPDATE TEI_OPUS SET DESCRIPTION = NULL
                WHERE DESCRIPTION LIKE '/9j/%' OR DESCRIPTION LIKE 'iVBORw0KGgo%' OR DESCRIPTION LIKE 'R0lGOD%\'''')
        }
    }
}
