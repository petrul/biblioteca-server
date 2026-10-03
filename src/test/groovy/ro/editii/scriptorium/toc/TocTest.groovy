package ro.editii.scriptorium.toc

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import ro.editii.scriptorium.TextbaseConfig
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.model.Author
import ro.editii.scriptorium.model.Languages
import ro.editii.scriptorium.model.TeiDiv
import ro.editii.scriptorium.tei.AuthorStrIdComputer
import ro.editii.scriptorium.tei.TeifileParser

import static ro.editii.scriptorium.GTestUtil.p

class TocTest {

    @Test
    void testToc() {

        final autorRep = Mockito.mock(AuthorRepository)
        final teifileRep = Mockito.mock(TeiFileRepository)
        final teidivRep = Mockito.mock(TeiDivRepository)
        final events = Mockito.mock(TextbaseEventsPublisher)
        final cfg = new TextbaseConfig()

        Author author = new Author(id: 1, firstName: 'Vasile', lastName: 'Alecsandri')
        Mockito
                .when(autorRep.getByOriginalNameInTeiFile('Alecsandri,Vasile'))
                .thenReturn(Optional.of(author))

        final List<TeiDiv> divs = []

        final parser = new TeifileParser(teifileRep,
                autorRep,
                teidivRep,
                events,
                cfg,
                new AuthorStrIdComputer(autorRep))

        long i = 0
        Mockito
                .when(teidivRep.saveAll(Mockito.any(List<TeiDiv>))).thenAnswer(new Answer<List<TeiDiv>>() {
            @Override
            List<TeiDiv> answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.arguments
                def batch = args[0]
                divs.addAll(batch)
                return batch
            }
                })
        Mockito
                .when(teidivRep.saveAllAndFlush(Mockito.anyCollection())).thenAnswer { invocation ->
                    def batch = invocation.arguments[0]
                    divs.addAll(batch)
                    batch
                }

        final URL res = this.getClass().getClassLoader().getResource("testrepo/ro/Alecsandri-Scrieri.xml")
        parser.parse("Alecsandri-Scrieri.xml",
                res.openStream(),
                Languages.CA)

        divs.forEach {it.id = ++i }

        p "*" * 80
        p divs

        final opuses = divs.findAll{ it.parent == null}
        assert divs.head.size() > 0
        divs.forEach {assert it.id != null }

        opuses.each { op ->
            assert op.dbChildren != null
            assert op.dbChildren.size() > 0
            final Toc toc = new Toc(op)
            println toc.asList().collect{it.head}.join("\n\t")
        }

        final nicolae_balcescu = divs.find{it.head == "Nicolae Bălcescu"}
        final constantin_Negruzzi = divs.find{it.head == "Constantin Negruzzi"}
        final merimee = divs.find{it.head == 'Prosper Mérimée'}
        final negruzzi_v = constantin_Negruzzi.dbChildren.find{ it.head == 'V'}
        final negruzzi_i = constantin_Negruzzi.dbChildren.find{ it.head == 'I'}
        final merimee_i = merimee.dbChildren.find { it.head == 'I'}

        final biografii = opuses.find {it.head == 'Biografii'}
        def toc = new Toc(biografii)
        def toc_heads = toc.collect {it.head}

        assert toc_heads == [
                "Biografii",
                        "Alecu Russo",
                            "I",
                            "II",
                            "III",
                        "Nicolae Bălcescu",
                        "Constantin Negruzzi",
                            "I",
                            "II",
                            "III",
                            "IV",
                            "V",
                        "Prosper Mérimée",
                            "I",
                            "II",
                            "III"
        ]

        assert toc.prev(constantin_Negruzzi) == nicolae_balcescu
        assert toc.next(constantin_Negruzzi) == negruzzi_i
        assert toc.next(nicolae_balcescu) == constantin_Negruzzi
        assert toc.prev(merimee) == negruzzi_v
        assert toc.next(merimee) == merimee_i
        assert toc.prev(merimee_i) == merimee

    }
}
