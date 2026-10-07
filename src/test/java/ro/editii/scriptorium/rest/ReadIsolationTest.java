package ro.editii.scriptorium.rest;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import ro.editii.scriptorium.web.DivController;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Derby has no MVCC: a browse read at the default READ_COMMITTED takes
 * shared locks and can queue for a whole import transaction - the
 * catalog/author outages seen while the autoimport sweep was running.
 * Every reading GET therefore runs READ_UNCOMMITTED (no locks at all -
 * it may see uncommitted mid-import data, which is acceptable for
 * listings recomputed on the next request). This pins that contract:
 * if someone adds or reverts a browse GET without the isolation, this
 * fails before the lockout reappears in production.
 */
class ReadIsolationTest {

    private static final Object[][] BROWSE_GETS = {
            {WorkCatalogRestController.class, "page"},
            {DivRestController.class, "get_by_path"},
            {DivRestController.class, "get_id"},
            {DivRestController.class, "getCoverMetadata"},
            {DivRestController.class, "get_id_toc"},
            {DivRestController.class, "get_id_paras"},
            {DivRestController.class, "getAllTeiDivs"},
            {AuthorRestController.class, "getAuthors"},
            {AuthorRestController.class, "getAuthorPage"},
            {AuthorRestController.class, "getAuthor"},
            {AuthorRestController.class, "getAllAuthorMedia"},
            {AuthorRestController.class, "getOpera"},
            {DivCollectionRestController.class, "mine"},
            {DivCollectionRestController.class, "get"},
            {DivCollectionRestController.class, "byLanguage"},
            {DivCollectionRestController.class, "byAuthor"},
            {DivCollectionRestController.class, "byRepo"},
            {DivCollectionRestController.class, "repoNames"},
            {DivCollectionRestController.class, "featured"},
            {DivController.class, "index"},
            {DivController.class, "get_authorId"},
            {DivController.class, "catchAllDivDispatcher"},
    };

    @Test
    void everyBrowseGetReadsDirtyInsteadOfBlocking() {
        for (Object[] pair : BROWSE_GETS) {
            assertNonblockingRead((Class<?>) pair[0], (String) pair[1]);
        }
    }

    private static void assertNonblockingRead(Class<?> controller, String method) {
        // getDeclaredMethods, not getMethods: at least one browse handler
        // (DivRestController.get_by_path) is package-private, invisible to
        // getMethods() - Spring MVC still maps it fine
        for (Method m : controller.getDeclaredMethods()) {
            if (!m.getName().equals(method) || m.getAnnotation(GetMapping.class) == null) continue;
            final Transactional tx = m.getAnnotation(Transactional.class);
            if (tx == null) fail(m + " has no @Transactional");
            assertTrue(tx.readOnly(), m + " must be readOnly");
            assertTrue(tx.isolation() == Isolation.READ_UNCOMMITTED, m + " must read READ_UNCOMMITTED");
            return;
        }
        fail("no @GetMapping method " + method + " on " + controller.getSimpleName());
    }
}
