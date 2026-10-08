package ro.editii.scriptorium.tei

import editii.commons.xml.XpathTool
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.ApplicationContext
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import ro.editii.scriptorium.InMemoryTeiRepo
import ro.editii.scriptorium.TextbaseConfig
import ro.editii.scriptorium.dao.AuthorRepository
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.dao.TeiFileRepository
import ro.editii.scriptorium.dao.TeiOpusRepository
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher
import ro.editii.scriptorium.dto.TeiDivDto
import ro.editii.scriptorium.model.Languages

import static ro.editii.scriptorium.GTestUtil.p
import static ro.editii.scriptorium.GTestUtil.teiOf
import static org.mockito.ArgumentMatchers.any
import static org.mockito.Mockito.times
import static org.mockito.Mockito.verify

@SpringBootTest(classes = [
    TeifileParser.class,
])
class TeifileParserTest {

    @MockitoBean TeiFileRepository teiFileRepository
    @MockitoBean TeiOpusRepository teiOpusRepository
    @MockitoBean AuthorRepository authorRepository
    @MockitoBean TeiDivRepository teiDivRepository
    @MockitoBean TextbaseEventsPublisher eventsPublisher
    @MockitoBean TextbaseConfig textbaseConfig
    @MockitoBean AuthorStrIdComputer authorStrIdComputer
    @Autowired TeifileParser parser

    @Autowired ApplicationContext applicationContext

    @Test
    void testAppCtxt() {
        assert this.applicationContext != null
        final names = this.applicationContext.beanDefinitionNames
        names.each {println it}
    }


    @Test
    void testParse() {

        final repo = new InMemoryTeiRepo()

        final tei1 = teiOf("""
            <div>
                <head>titlu aici</head>
                <p> This is a paragraph1 outside of inner divs</p>
                <div>
                    <head>h2</head>
                    <p> hei</p>
                    <div>
                        <head>titlu subch level 3 </head>
                        foaie verde
                        foaie lata
                    </div>
                </div>
            </div>
        """)
        final key1 = 'key1'

        repo[key1] = tei1

        p this.parser
        final divs = this.parser.parse(key1, tei1, Languages.RO)

        p divs.size()

        divs.each {
            it.teiRepo = repo
        }

        final node = divs.first().node
        final xt = new XpathTool(node)
//        node.get
//        p "asdasd $node qweqwe"
//        final xt = new XpathTool(node)
//        p xt.applyXpathForString('//text()')
//        p xt.applyXpathForNodeSet('/tei:TEI//text()').length
//        p nodeSet2List(xt.applyXpathForNodeSet('/tei:TEI//text()'))
//
//        p node
//        p node.namespaceURI
//        p node.nodeName

//        p "children:"
//        n2l(node.childNodes).each {
//            p "============"
//            p it.nodeType
//            p it.nodeName
//            p it.namespaceURI
//            p it.childNodes.length
//            p it
//        }
//        p ">>>>>>>>>>>>>>"

        p xt.xpath_jl("tei:p")
        p xt.xpath_jl(".")
        p '==='
        xt.xpath_jl("*").each {
            p it.nodeType
            p it.nodeName
        }

        p '=='
        xt.xpath_jl("tei:*[position() = 3]").each {
            p it.nodeType
            p it.nodeName
        }

        p '=='
        xt.xpath_jl("*[3]").each {
            p it.nodeType
            p it.nodeName
        }
    }

    @Test
    void publishesRootOpusEvenWhenDatabaseBatchIsFlushed() {
        given:
        def children = (1..1000).collect { n ->
            "<div><head>child ${n}</head><p>text</p></div>"
        }.join('\n')
        def tei = teiOf("""
            <div>
                <head>large opus</head>
                ${children}
            </div>
        """)

        when:
        def imported = parser.parse('large-opus.xml', tei, Languages.RO)

        then:
        imported.size() == 1001
        verify(eventsPublisher, times(1)).signalNewOpusImported(any(TeiDivDto.class))
    }

    @Test
    void labelOnlyHeadBecomesTheDivHead() {
        given: 'Gutenberg-style chapters whose head is nothing but a label'
        def tei = teiOf("""
            <div>
                <head>The Last of the Mohicans</head>
                <div>
                    <head>INTRODUCTION</head>
                    <p>intro</p>
                </div>
                <div>
                    <head>
                        <label>CHAPTER I.</label>
                    </head>
                    <p>chapter one</p>
                </div>
                <div>
                    <head><label>IV</label> Fuga</head>
                    <p>labelled chapter with its own title</p>
                </div>
                <div>
                    <head/>
                    <p>odt2tei empty-head wrapper</p>
                </div>
            </div>
        """)

        when:
        def imported = parser.parse('label-heads.xml', tei, Languages.RO)

        then: 'a label-only head is titled by its label; label + title keeps the title only; an empty head is still skipped'
        assert imported*.head == ['The Last of the Mohicans', 'INTRODUCTION', 'CHAPTER I.', 'Fuga']
    }

    static List<Node> n2l(NodeList nodeList) {
        return nodeSet2List(nodeList)
    }

    static List<Node> nodeSet2List(NodeList nodeList) {
        final List<Node> resp = []
        for (int i = 0; i < nodeList.length; i++) {
            resp << nodeList.item(i)
        }
        resp
    }

}
