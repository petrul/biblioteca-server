package ro.editii.scriptorium.service

import org.junit.jupiter.api.Test
import ro.editii.scriptorium.dao.RelocationRepository
import ro.editii.scriptorium.model.Relocation

import static ro.editii.scriptorium.service.RelocationService.Division
import static ro.editii.scriptorium.service.RelocationService.Snapshot

/** RelocationService's matching and resolution without Spring or a database. */
class RelocationMatchingTest {

    static Division div(String path, String head, int position, String digest = null, int occurrence = 1) {
        final parent = path.count('/') > 2 ? path.substring(0, path.lastIndexOf('/')) : null
        new Division(path, parent, RelocationService.normalize(head), position, "a/${RelocationService.normalize(head)}", occurrence, digest)
    }

    @Test
    void pathsAreCanonical() {
        assert RelocationService.canonical('shakespeare/the_tragedy/act_iv.html?depth=0') == '/shakespeare/the_tragedy/act_iv'
        assert RelocationService.canonical('//shakespeare//the_tragedy/') == '/shakespeare/the_tragedy'
        assert RelocationService.canonical('/') == '/'
    }

    @Test
    void headingsAreSimilarWhenOneGainedALabel() {
        assert RelocationService.similarity('another room in the castle', 'scene ii another room in the castle') == 1
        assert RelocationService.similarity('a plain in denmark', 'another room in the castle') < RelocationService.SIMILAR_HEADINGS
        assert RelocationService.similarity('', 'anything') == 0
    }

    @Test
    void aTotalRewriteMatchesNothingAndThrowsNothing() {
        final before = new Snapshot('f.xml', [div('/a/w', 'Work', 0, 'd0'), div('/a/w/one', 'One', 0, 'd1'), div('/a/w/two', 'Two', 1, 'd2')])
        final after = new Snapshot('f.xml', [div('/a/z', 'Something else', 0, 'x0'), div('/a/z/x', 'Unrelated', 0, 'x1')])
        assert RelocationService.match(before, after).isEmpty()
        assert RelocationService.displaced(before, after).isEmpty()
        assert RelocationService.match(Snapshot.empty('f.xml'), after).isEmpty()
        assert RelocationService.match(before, Snapshot.empty('f.xml')).isEmpty()
    }

    @Test
    void sameTextWinsOverPositionAndOneTargetTakesOneOldPath() {
        final before = new Snapshot('f.xml', [div('/a/w', 'W', 0, 'w'), div('/a/w/old', 'Old', 0, 'same'), div('/a/w/gone', 'Gone', 1, 'g')])
        final after = new Snapshot('f.xml', [div('/a/w', 'W', 0, 'w'), div('/a/w/new', 'New', 0, 'same'), div('/a/w/gone2', 'Gone', 1, 'other')])
        final moved = RelocationService.match(before, after)
        assert moved['/a/w/old'] == '/a/w/new'
        assert moved['/a/w/gone'] == '/a/w/gone2' // same headings, edited text
        assert moved.values().toSet().size() == moved.size()
    }

    @Test
    void resolutionFollowsChainsAndRefusesLoops() {
        final rows = ['/a/w/1': '/a/w/2', '/a/w/2': '/a/w/3', '/a/w/x': '/a/w/y', '/a/w/y': '/a/w/x']
        final repository = [findById: { String id -> Optional.ofNullable(rows[id]).map { new Relocation(id, it, 'MOVED', null) } }] as RelocationRepository
        final service = new RelocationService(repository, null, null, null, null)
        assert service.resolve('/a/w/1').get().newPath == '/a/w/3'
        assert service.resolve('a/w/1.html').get().oldPath == '/a/w/1'
        assert service.resolve('/a/w/x').empty // a loop is no relocation
        assert service.resolve('/a/w/3').empty
        assert service.resolve('/').empty
    }
}
