package ro.editii.scriptorium.tei

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import ro.editii.scriptorium.TestConfig
import ro.editii.scriptorium.TestUtils
import ro.editii.scriptorium.dao.TeiDivRepository
import ro.editii.scriptorium.model.Languages

import static ro.editii.scriptorium.GTestUtil.*
import static ro.editii.scriptorium.TestUtils.TEI_ELEM

@TestPropertySource(properties=[
        "spring.datasource.url=jdbc:derby:memory:myDb;create=true",
        "spring.datasource.driver-class-name=org.apache.derby.iapi.jdbc.AutoloadedDriver",
        "spring.jpa.database-platform=org.hibernate.community.dialect.DerbyDialect",
        "spring.jpa.hibernate.ddl-auto = create",
        "spring.main.allow-bean-definition-overriding=true"])
@SpringBootTest(classes = [ TestConfig.class ])
@EnableAutoConfiguration(exclude= [KafkaAutoConfiguration.class])
class ParseTeiFileIntoMemDbTest {

    @Autowired TeiDivRepository teiDivRepository
    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TeifileParser teifileParser

    @BeforeEach
    void before() {

        // DELETE FROM, not TRUNCATE + SET FOREIGN_KEY_CHECKS - see
        // AdminService.destroyAllExistingAndReimportAllTeis's own comment.
        this.jdbcTemplate.update("DELETE FROM ${TestUtils.TEI_FILE_AUTHORS}")
        this.jdbcTemplate.update("DELETE FROM author")
        this.jdbcTemplate.update("DELETE FROM tei_opus")
        this.jdbcTemplate.update("DELETE FROM ${TestUtils.TEI_ELEM}")
        this.jdbcTemplate.update("DELETE FROM ${TestUtils.TEI_FILE}")

        assert countTableRows(this.jdbcTemplate, "author") == 0
        assert countTableRows(this.jdbcTemplate, TestUtils.TEI_FILE_AUTHORS) == 0
        assert countTableRows(this.jdbcTemplate, TestUtils.TEI_ELEM) == 0
    }

    @Test
    void disabledChapters() {
        assert TeifileParser.isMarkedWithX("[x] foaie verde")
        assert TeifileParser.isMarkedWithX("[X] foaie verde")
        assert TeifileParser.isMarkedWithX("[Xx] foaie verde")
        assert TeifileParser.isMarkedWithX("foaie [Xx] verde")
        assert TeifileParser.isMarkedWithX("foaie [x] verde")
        assert TeifileParser.isMarkedWithX("foaie verde  [Xx] ")
        assert TeifileParser.isMarkedWithX("foaie verde  [x] ")
        assert TeifileParser.isMarkedWithX("foaie verde  [x]")
        assert TeifileParser.isMarkedWithX("foaie verde  [X] ")
        assert TeifileParser.isMarkedWithX("foaie verde  /X/ ")
        assert TeifileParser.isMarkedWithX("foaie verde  /x/ ")
        assert TeifileParser.isMarkedWithX("foaie verde  |x| ")
        assert TeifileParser.isMarkedWithX("foaie verde  |X| ")

        final tei = """
        <TEI xmlns="http://www.tei-c.org/ns/1.0">
           <teiHeader>
              <fileDesc>
                 <titleStmt>
                    <title>Собрание сочинений</title>
                    <author>Достоевский, Федор Михайлович</author>
                 </titleStmt>
              </fileDesc>
           </teiHeader>
           <text>                    
              <body>
                 <head>file Title</head>
                 <author>Достоевский, Федор Михайлович</author>
                 <subtitle>1988</subtitle>
                 
                 <div type="div1">
                    <head>This heading will pass OK</head>
                    <subtitle>( Собрание сочинений в пятнадцати томах - 1 )</subtitle>
                    <p rend="justify">Федор Михайлович Достоевский</p>
                 </div>
                 <div type="div1">
                    <head>This heading will be ignored [x]</head>
                    <subtitle>( Собрание сочинений в пятнадцати томах - 1 )</subtitle>
                    <p rend="justify">Федор Михайлович Достоевский</p>
                 </div>
                 <div type="div1">
                    <head>This heading will also be ignored [X]</head>
                    <subtitle>( Собрание сочинений в пятнадцати томах - 1 )</subtitle>
                    <p rend="justify">Федор Михайлович Достоевский</p>
                 </div>
                 <div type="div1">
                    <head>This heading will also be ignored [X]</head>
                 </div>
                 <div type="div1">
                        <head>This heading will also be ignored [Xx]</head>
                        <subtitle>( Собрание сочинений в пятнадцати томах - 1 )</subtitle>
                        <p rend="justify">Федор Михайлович Достоевский</p>
                 </div>
                 <div type="div1">
                        <head>But this will pass x X]</head>
                        <subtitle>( Собрание сочинений в пятнадцати томах - 1 )</subtitle>
                        <p rend="justify">Федор Михайлович Достоевский</p>
                 </div>
                 <div type="div1">
                        <head>I d like this to be ignored /x/ too</head>
                        <subtitle>( Собрание сочинений в пятнадцати томах - 1 )</subtitle>
                        <p rend="justify">Федор Михайлович Достоевский</p>
                 </div>
                 
         </body></text></TEI>
        """

        this.teifileParser.parse(tei, Languages.RU)
        assert countTeiElem() == 2

        final list = this.jdbcTemplate.queryForList("select head from $TEI_ELEM", String.class)
        list.each {p it}
        assert list.find {it.contains('[')} == null
    }

    @Test
    void dumasSkipsABook() {
        final tei = """
        <TEI xmlns="http://www.tei-c.org/ns/1.0">
           <teiHeader>
              <fileDesc>
                 <titleStmt>
                    <title>title</title>
                    <author>Dumas,Alexandre</author>
                 </titleStmt>
              </fileDesc>
           </teiHeader>
           <text>                    
              <body>
                 <head>file Title</head>
                 <author>Dumas,Alexandre</author>
                 <subtitle>1988</subtitle>
                 
         <div type="div1">
            <head>
               <hi rend="bold">La princesse </hi>
               <hi rend="bold">F</hi>
               <hi rend="bold">lora</hi>
            </head>
            <subtitle>(1862)</subtitle>
            <div type="div2" rend="P274">
               <head>
                  <anchor type="bookmark-start" xml:id="id___RefHeading___Toc86913708"/>I<lb/>La princesse Flora à sa parente, à Moscou.<ptr type="bookmark-end" target="#id___RefHeading___Toc86913708"/>
               </head>
               <p>Je suis furieuse contre Moscou, ma chère, parce que tu n'es pas avec moi. Je dois te raconter une foule de choses… mais comment te les écrire ? J'ai tant vu et tant vécu depuis une semaine ! D'abord, j'ai été mortellement triste : rien n'est plus ennuyeux qu'un continuel étonnement. La cour impériale et le grand monde me donnent le vertige, et j'en suis arrivé à entendre sans m'émerveiller la plus énorme sottise, comme à contempler sans sourire le plus curieux tableau ; mais la fête de Peterhoff, Peterhoff lui-même, c'est une exception, la perle des exceptions jusqu'à présent… J'ai tout vu ; j'ai été partout ; j'ai les oreilles assourdies du bruit du canon, des cris du peuple, du murmure des fontaines, du rebondissement des cascades… Nous avons lu avec attention, nous avons dévoré avec gourmandise ensemble, tu te le rappelles, la description des miracles de Peterhoff ; mais, quand j'ai vu de mes propres yeux toutes ces merveilles, elles m'ont littéralement dévorée, et j'ai tout oublié, même toi, mon bel ange ; j'ai rebondi dans les airs avec la cascade ; j'ai monté jusqu'au ciel avec sa poussière ; je suis redescendue sur la terre, légère comme la goutte de rosée ; j'ai jeté mon ombre céleste et odoriférante, sur les allées pleines de souvenirs ; j'ai joué avec les rayons du soleil et avec les vagues de la mer ; et tout cela, c'était le jour ; et quelle nuit a couronné ce jour ! Il fallait s'étonner en voyant comme peu à peu s'allumait l'illumination ; il semblait qu'un doigt de feu dessinât de merveilleux dessins sur le voile noir de la nuit ; elle s'épanouissant en fleurs, s'arrondissait en roue, rampait en serpent, et, tout à coup, voilà que tout le jardin fut en feu. Tu eusses dit, ma chère, que le soleil était tombé du ciel sur la terre et s'y était éparpillé en étincelles ; les flammes avaient entouré les arbres, mais des couronnes d'étoiles aux pièces d'eau ; les fontaines étaient des volcans et les montagnes des mines d'or ; les canaux et les bassins s'en imbibaient avidement, reproduisaient les dessins et les doublaient ; et arbres, pièces d'eau, fontaines, montagnes, canaux et bassins semblaient rouler un immense incendie. Les clameurs du peuple, jointes au bruit des cascades et au frémissement des arbres, vivifiaient ce splendide spectacle par leur majestueuse harmonie : c'était la voix de Circé, c'était le chant des sirènes.</p>
               <p>À onze heures du soir, tout l'Olympe descendit à terre ; de longues files de voitures serpentaient dans les jardins, et les resplendissantes dames de la cour qui les occupaient, pareilles à des files de perles, semblaient un rêve de poète, tant elles étaient légères et presque transparentes. Et, moi-même, j'étais une de ces sylphides ! J'avais une robe de brocart, – qu'on appelle à la cour, je ne sais pourquoi, robe russe, – avec un dessous de satin blanc, garni de piqués d'or ; cette robe, ma chère Sophie, était si bien coupée, si bien brodée, qu'avant de la vêtir, j'eus envie de me mettre à genoux devant ; j'étais coiffée avec des marabouts, présent de mon mari, et je te dirai, sans vanité aucune, que cette coiffure m'allait à merveille ; et, quand même je ne m'en fusse pas rapportée à mon miroir, le murmure des hommes sur mon passage eût pu convaincre l'apôtre Thomas lui-même que ta cousine était très gentille.</p>
           </div>
       </div>
       </body></text></TEI>
        """
        this.teifileParser.parse(tei, Languages.ES)

        assert countTeiElem() > 0
        assert this.jdbcTemplate.queryForList("select head from $TEI_ELEM", String.class).size() > 0

        final list = this.jdbcTemplate.queryForList("select head from $TEI_ELEM", String.class)
        assert list.size() == 2
        assert list.first() == 'La princesse Flora'
        assert list.find {it.contains('[')} == null
        assert list.get(1) == 'I La princesse Flora à sa parente, à Moscou.'

        // also assert hierarchy is kept
        final div2 = this.teiDivRepository.findByHead('I La princesse Flora à sa parente, à Moscou.').first()
        final div1 = this.teiDivRepository.findByHead('La princesse Flora').first()
        assert div2.parent.head == div1.head
    }

    @Test
    void spaceAtTheBeginningOfHead() {
        final tei = teiOf("""
            <div type="div2" rend="P16">
               <head>         Cuvânt înainte.</head>
               <subtitle> „Uraganul ridicat de semilună.”</subtitle>
               <p rend="justify">         S-a împlinit, Ia 1</p>
           </div>
        """)
        this.teifileParser.parse(tei, Languages.ES)

        final parsedHead = this.jdbcTemplate.queryForList("select head from ${TestUtils.TEI_ELEM}", String.class)[0]
        assert parsedHead == 'Cuvânt înainte.'
    }

    /**
     * if I marked an upper-level head with /x/, do not recurse into its
     * div children, even if they are not marked with /x/
     */
    @Test
    void subchapterOfXdHeadWillNotBeRecursedinto() {
        final tei = teiOf("""
            <div>
                <head>this is /x/'d out</head>
                <div>
                    <head>this is a subchapter and should not be recursed into</head>
                    <p> some content</p>
                </div>
            </div>
        """)
        this.teifileParser.parse(tei, Languages.PT)

        assert 0 == this.countTeiElem()
    }

    @Test
    void figureRemovedFromHead() {
        final tei = teiOf("""
            <div type="div1" rend="P9">
                <head>
                    <figure>
                        <binaryObject xml:id="d3e3096" encoding="base64" mimeType="image/jpg">iVBORw0KGgoAAAANSUhEUgAAAP0AAAFeCAIAAADBqI5fAAAAAXNSR0IArs4c6QAAAAlwSFlz
                            AAALEwAACxMBAJqcGAAA/7VJREFUeF7s3dezdVlZ73ERc845ZwSbJDQ0NI3QtICCFGV55
                        </binaryObject>
                    </figure>
                    Pula calului
                </head>
            </div>
        """)
        this.teifileParser.parse(tei, Languages.LA)

        assert 1 == countTeiElem()
        assert "Pula calului" ==  this.jdbcTemplate.queryForList("select head from ${TEI_ELEM}", String.class).get(0)
    }

    protected int countTeiElem() {
        this.jdbcTemplate.queryForObject("select count(*) from ${TEI_ELEM}", Integer.class)
    }

    @Test
    void binaryObjectRemovedFromHead() {
        final tei = teiOf("""
            <div type="div1" rend="P9">
                <head>
                    <binaryObject xml:id="d3e3096" encoding="base64" mimeType="image/jpg">iVBORw0KGgoAAAANSUhEUgAAAP0AAAFeCAIAAADBqI5fAAAAAXNSR0IArs4c6QAAAAlwSFlz
                        AAALEwAACxMBAJqcGAAA/7VJREFUeF7s3dezdVlZ73ERc845ZwSbJDQ0NI3QtICCFGV55
                    </binaryObject>
                    Pula calului
                </head>
            </div>
        """)

        this.teifileParser.parse(tei, Languages.BG)

        assert 1 == countTeiElem()
        assert "Pula calului" ==  this.jdbcTemplate.queryForList("select head from ${TestUtils.TEI_ELEM}", String.class).get(0)

    }

}
