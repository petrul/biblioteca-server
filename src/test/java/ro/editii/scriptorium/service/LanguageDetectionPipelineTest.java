package ro.editii.scriptorium.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.editii.scriptorium.model.Languages;
import ro.editii.scriptorium.tei.TeiDirRepoImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import-time language pipeline: Lingua content detection first, the
 * directory-name hint as fallback - exactly TeiFileDbService.importTeiFile's
 * composition, {@code detect(content).orElseGet(() -> getLanguageHint(name))}.
 *
 * Regression shapes, all taken from the krudy/bukfenc case: Hungarian books
 * under a hu/ directory whose TEI declares no language, whose historical
 * stored language came out Latin (LA). The mechanism: detect() used to
 * sample the RAW XML prefix - mostly teiHeader boilerplate and bare roman
 * labels ("XI.") that Lingua confidently reads as Latin - so detection
 * "succeeded" and the hu/ directory hint never ran.
 */
class LanguageDetectionPipelineTest {

    private final LanguageDetectionService detector = new LanguageDetectionService();

    private Path repoDir;
    private TeiDirRepoImpl repo;

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() throws IOException {
        this.repoDir = this.temp.resolve("tei");
        Files.createDirectories(this.repoDir);
        this.repo = new TeiDirRepoImpl(this.repoDir.toString());
    }

    private void mockRepoFile(String teiFilename, String content) throws IOException {
        final Path file = this.repoDir.resolve(teiFilename.startsWith("/")
                ? teiFilename.substring(1) : teiFilename);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** The import-time composition, in lockstep with TeiFileDbService.importTeiFile. */
    private Languages importTimeLanguage(String teiFilename, String content) {
        return this.detector.detect(content)
                .orElseGet(() -> this.repo.getLanguageHint(teiFilename));
    }

    /**
     * Hungarian TEI with no declared language, exactly like
     * /hu/gutenberg/krudy,gyula-bukfenc.xml: no tei:language anywhere, real
     * Hungarian prose in the body.
     */
    private static String hungarianTei(String head, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <TEI xmlns="http://www.tei-c.org/ns/1.0">
                  <teiHeader>
                    <fileDesc>
                      <titleStmt><title>%s</title></titleStmt>
                    </fileDesc>
                  </teiHeader>
                  <text><body>
                    <div type="div2"><head><label>XI.</label><lb/>%s</head>
                    <p rend="justify">%s</p>
                    </div>
                  </body></text>
                </TEI>
                """.formatted(head, head, body);
    }

    /**
     * The faithful bukfenc shape: a Gutenberg-style header long enough to
     * fill the whole 3000-char sample window with English/label boilerplate
     * (publisher, source, revision statements - no language declared),
     * with the Hungarian prose only starting well after </teiHeader>.
     */
    private static String longBoilerplateHeaderTei(String body) {
        final String boilerplate = """
                <teiHeader>
                  <fileDesc>
                    <titleStmt>
                      <title>Gyöngyvirág elszállása</title>
                      <author>Krúdy, Gyula, 1878-1933</author>
                    </titleStmt>
                    <publicationStmt>
                      <publisher>Project Gutenberg</publisher>
                      <date>Posted on: %s</date>
                      <availability><p>This eBook is for the use of anyone
                        anywhere in the United States and most other parts
                        of the world at no cost and with almost no
                        restrictions whatsoever. You may copy it, give it
                        away or re-use it under the terms of the Project
                        Gutenberg License included with this eBook or
                        online at www.gutenberg.org. If you are not located
                        in the United States, you will have to check the
                        laws of the country where you are located before
                        using this ebook.</p></availability>
                    </publicationStmt>
                    <sourceDesc>
                      <p>Produced by Distributed Proofreaders. Reading
                        requires completion of the transcription, marks,
                        abbreviations and footnote verification. First
                        posted in the collection. See the transcriber's
                        notes appended at the end of this volume for
                        details about the source edition used, the
                        collation of variants and the list of corrections
                        applied during preparation of this etext.</p>
                    </sourceDesc>
                  </fileDesc>
                  <encodingDesc>
                    <p>The following provides details about the source text
                      and editorial decisions applied during the
                      preparation of this electronic edition. Spelling has
                      been preserved as it appears in the original printed
                      book, including older orthographic conventions, with
                      the exception of obvious typographical errors which
                      have been corrected silently where the reading of the
                      source edition is unambiguous. Where the correction
                      required an editorial judgment, the original form has
                      been retained and the proposed emendation recorded in
                      the textual notes. Hyphenation has been retained for
                      compound words that appear at the line breaks of the
                      printed edition, and paragraph indentation has been
                      normalized throughout the volume. Footnotes have been
                      renumbered sequentially and are anchored at the exact
                      point of the printed edition wherever the anchor could
                      be established with certainty. Illustrations have
                      been repositioned to fall at paragraph breaks, and
                      their captions transcribed exactly as printed.
                      References to page numbers of the source edition
                      are retained in the margin as editorial
                      annotations, so that readers consulting the
                      printed volume alongside this etext can locate the
                      corresponding passage without difficulty or
                      ambiguity of any kind whatsoever.</p>
                  </encodingDesc>
                  <revisionDesc>
                    <change><date>%s</date><name>PG Distributed Proofreaders Team</name>
                      <item>Revision: proofreading pass eleven completed,
                        corrections applied, illustrations renumbered and
                        the table of contents entries checked against the
                        printed edition of the source book.</item></change>
                  </revisionDesc>
                </teiHeader>
                """.formatted("2026-10-06", "2026-10-06");
        // Sanity for the regression shape: the header alone must exceed the
        // detection sample window, so prose is only reachable via body sampling.
        assertThat(boilerplate.length()).isGreaterThan(3000);

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <TEI xmlns="http://www.tei-c.org/ns/1.0">
                %s
                <text><body>
                  <div type="div2"><head><label>XI.</label><lb/>Gyöngyvirág elszállása</head>
                  <p rend="justify">%s</p>
                  </div>
                </body></text>
                </TEI>
                """.formatted(boilerplate, body);
    }

    @Test
    void hungarianExcerptsWithoutDeclaredLanguageAreAssignedHungarian() throws IOException {
        final String bukfencBody =
                "A nagymama napjai meg voltak számlálva, mint a tojások a kamrában. "
                + "Össze volt hajtogatva már az öregasszony, mint egy birósági kékpapiros, "
                + "amelyben az itéletet közlik a delikvenssel. Esténként mindig tollbokrétás, "
                + "ünnepélyes halottaskocsik árnyképei mutatkoztak az őszi ködben. "
                + "A nagymama ijedten nézte a leereszkedő setétséget és borzadt.";
        final String postakocsiBody =
                "Kisvárosban éldegélt a szép leány, aki szerette a nyári alkonyatokat, "
                + "amikor a piacra járó gazdasszonyok hazatérnek, és a kocsmai pikulák "
                + "csengő-bongó hangjával elvegyül a templomharang szava. "
                + "Minden esztendőben úgy érezte, hogy egyre többen lesznek, akiket ismer, "
                + "és egyre kevesebben, akiket szeret.";
        final String szindbadBody =
                "Az öreg utazó visszagondolt ifjú éveire, amikor még a Duna-parti "
                + "fogadókban hallgatta a muzsikusokat, és a borospincék mélyén "
                + "fényes nappal aludták át a világ minden gondját. "
                + "A szeme előtt úsztak el a régi városkák, templomtornyok, szélben "
                + "lobogó ágaskodó lovak és azok a fehér inges legények.";

        final String[] filenames = {
                "/hu/gutenberg/krudy,gyula-bukfenc.xml",
                "/hu/gutenberg/krudy,gyula-a_voros_postakocsi.xml",
                "/hu/gutenberg/krudy,gyula-szindbad.xml"};
        mockRepoFile(filenames[0], hungarianTei("Gyöngyvirág elszállása", bukfencBody));
        mockRepoFile(filenames[1], hungarianTei("A vörös postakocsi", postakocsiBody));
        mockRepoFile(filenames[2], hungarianTei("Szindbád", szindbadBody));

        for (final String filename : filenames) {
            final String content = Files.readString(
                    this.repoDir.resolve(filename.substring(1)), StandardCharsets.UTF_8);

            assertThat(this.importTimeLanguage(filename, content))
                    .as("import-time language for %s", filename)
                    .isEqualTo(Languages.HU);
        }
    }

    @Test
    void bukfencShapeWithBoilerplateHeaderStillLandsOnHungarian() throws IOException {
        // The exact historical failure shape: header > sample window (so the
        // old raw-prefix sampling only ever saw the header), bare "XI."
        // labels, no declared language, Hungarian prose after </teiHeader>.
        final String filename = "/hu/gutenberg/krudy,gyula-bukfenc.xml";
        final String content = longBoilerplateHeaderTei(
                "A nagymama napjai meg voltak számlálva, mint a tojások a kamrában. "
                + "Össze volt hajtogatva már az öregasszony, mint egy birósági kékpapiros, "
                + "amelyben az itéletet közlik a delikvenssel. Esténként mindig tollbokrétás, "
                + "ünnepélyes halottaskocsik árnyképei mutatkoztak az őszi ködben.");
        mockRepoFile(filename, content);

        assertThat(this.importTimeLanguage(filename, content))
                .isEqualTo(Languages.HU);
    }

    @Test
    void labelLikeSampleDefersToTheHuDirectoryHint() throws IOException {
        // A bare roman label alone ("XI.", the exact string Lingua calls
        // Latin with full confidence) is too little prose to trust - the
        // pipeline must defer to the /hu/ directory fragment.
        final String filename = "/hu/gutenberg/krudy,gyula-tartalom.xml";
        final String content = "<?xml version=\"1.0\"?><TEI><teiHeader/><text><body>"
                + "<div><p>XI.</p></div></body></text></TEI>";
        mockRepoFile(filename, content);

        assertThat(this.detector.detect(content))
                .as("a bare label is not enough signal for content detection")
                .isEmpty();
        assertThat(this.importTimeLanguage(filename, content))
                .isEqualTo(Languages.HU);
        // The hint alone says the same, straight from the path.
        assertThat(this.repo.getLanguageHint(filename))
                .isEqualTo(Languages.HU);
    }

    @Test
    void genuineLatinProseIsDetectedAsLatinEvenUnderAHuDirectory() throws IOException {
        // Lingua DOES ship a Latin model (despite the old comment's claim):
        // real Latin prose must win over any directory hint - the
        // detection-vs-hint priority only cuts the other way for label-like
        // or header-like noise, never for genuine prose.
        final String latinBody =
                "Gallia est omnis divisa in partes tres, quarum unam incolunt Belgae, "
                + "aliam Aquitani, tertiam qui ipsorum lingua Celtae, nostra Galli "
                + "appellantur. Hi omnes lingua, institutis, legibus inter se differunt.";
        final String filename = "/hu/miscellanea/caesar,fragmentum.xml";
        mockRepoFile(filename, hungarianTei("Fragmentum", latinBody));

        assertThat(this.importTimeLanguage(filename, latinBodyWrap(latinBody)))
                .isEqualTo(Languages.LA);
    }

    private static String latinBodyWrap(String body) {
        return "<?xml version=\"1.0\"?><TEI><teiHeader/><text><body><p>"
                + body + "</p></body></text></TEI>";
    }
}
