package ro.editii.scriptorium.service

import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.model.TeiFile
import ro.editii.scriptorium.tei.TeiRepo

/**
 * The post-import hooks (incremental Lucene reindex, author language
 * backfill) load whole opera - with @Lob fields PostgreSQL can only read
 * inside a transaction ("Unable to access lob stream" made every import skip
 * them) - so they must run in one. Groovy map-coerced mocks, no Spring.
 */
class AdminServicePostImportHooksTest {

    final List<String> events = []
    boolean inTransaction = false

    final PlatformTransactionManager transactions = [
            getTransaction: { TransactionDefinition d -> inTransaction = true; events << 'begin'; new SimpleTransactionStatus() },
            commit        : { TransactionStatus s -> inTransaction = false; events << 'commit' },
            rollback      : { TransactionStatus s -> inTransaction = false; events << 'rollback' },
    ] as PlatformTransactionManager

    private AdminService service(Closure loadOpera) {
        def teiFile = new TeiFile()
        teiFile.id = 9L
        teiFile.authors = []
        def files = [getByFilename: { String f -> Optional.of(teiFile) }] as TeiFileRepository
        def divs = [getOperaForTeiFileId: loadOpera] as TeiDivRepository
        def admin = new AdminService([:] as TeiRepo, files, divs, null, null, null, [:] as AuthorRepository, [:] as TextbaseEventsPublisher)
        admin.incrementalLuceneEnabled = false
        admin.transactionTemplate = new TransactionTemplate(transactions)
        admin
    }

    @Test
    void theOperaAreLoadedInsideATransaction() {
        def admin = service({ long id -> events << "load opera (in transaction: ${inTransaction})".toString(); [] as List<TeiDiv> })
        admin.postImportHooks('/ro/anon-poezii_populare.tei.xml')
        assert events == ['begin', 'load opera (in transaction: true)', 'commit']
    }

    @Test
    void aFailingHookNeverAbortsTheImport() {
        def admin = service({ long id -> throw new IllegalStateException('Unable to access lob stream') })
        admin.postImportHooks('/ro/anon-poezii_populare.tei.xml') // must not throw
        assert events == ['begin', 'rollback']
    }
}
