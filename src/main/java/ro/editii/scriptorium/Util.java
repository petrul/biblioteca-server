package ro.editii.scriptorium;

import com.ibm.icu.text.Transliterator;
import editii.commons.xml.XpathTool;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.service.ElemInfo;
import ro.editii.scriptorium.web.DivController;
import ro.editii.scriptorium.xslt.XsltTool;

import javax.xml.transform.Transformer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class Util {

    public static final String DIV = "div";
    public static final String BINARY_OBJECT = "binaryObject";
    public static final String TEI_ELEM = "tei_elem";

    /**
     * windows 11-resistent way to get the file path of a URL representing a file
     */
    public static String urlToFileString(URL url) {
        try {
            final URI uri = url.toURI();
            return Paths.get(uri).toString();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static String base64Encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] base64Decode(String s) {
        String str = s.trim()
            .replaceAll("\n", "")
            .replaceAll("\r", "")
            .replaceAll(" ", "")
            .replaceAll("\t", "");
        return Base64.getDecoder().decode(str);
    }

    public static String replaceTilde(String s) {
        return s.replaceFirst("^~", System.getProperty("user.home"));
    }

    /**
     * @return url URL slug. i.e "Nicolae Bălcescu" => nicolae_balcescu
     */
    public static String urlFriendify(String s) {
        assert s != null;

        s = StringUtils.stripAccents(s);
        s = transliterateCyrillic(s);
        s = transliterateGreek(s);
        s = transliterateAll(s);

        String res = s
                .trim()
                .toLowerCase()
                .replaceAll("[ăàαªâā]", "a")
                .replaceAll("æ", "ae")
                .replaceAll("[çćĉċč]", "c")
                .replaceAll("[éèêëē]", "e")
                .replaceAll("[ġĝğģǧǵ]", "g")
                .replaceAll("[îìíī]", "i")
                .replaceAll("[ṁ]", "m")
                .replaceAll("[ñṇņňŉ]", "n")
                .replaceAll("[øöôòº°ō]", "o")
                .replaceAll("œ", "oe")
                .replaceAll("[șşśŜŝš]", "s")
                .replaceAll("ß", "ss")
                .replaceAll("[țţṭ]", "t") // two different kinds of t-cedilla
                .replaceAll("[ûũūùúŭüǔ]", "u")
        ;

        res = res
                .replaceAll("\\.+$", "") // remove final dots
                .replaceAll("[\\(\\)]", "")
                .replaceAll("[·\\*]", "")
                .replaceAll("[§¡!?¿`«»<>'ʹ=…ʺ`~]", "")
                .replaceAll("[’]", "_")
                .replaceAll("[\\?\\!\\[\\]\\\"‘'„”“]", "")
                .replaceAll("½", "1_2")
                .replaceAll("¾", "3_4")
                .replaceAll("%", " percent ")
                .replaceAll("#", " hash ")
                .replaceAll("\\+", "_")
                .replaceAll("—", "_")
                .replaceAll("-", "_")
                .replaceAll("–", "_") // \u8211
                .replaceAll("\u00A0", "") // remove non-breakable space 0x00A0
        ;

        res  = res
                .replaceAll("[,\\.;:]", "_")
                .replaceAll("&", "_")
                .replaceAll("\\p{M}", "") // remove accents
                .trim() // again, to remove trailing spaces
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_")
            ;

        // trim _ at the beginning and the end
        res = res
                .replaceAll("^_+", "")
                .replaceAll("_+$", "")
        ;



        return res;
    }

    final static Transliterator cyrillicToLatinTrans = Transliterator.getInstance("Russian-Latin/BGN");
    public static String transliterateCyrillic(String s) {
        if (containsCyrillic(s)) {
            s = cyrillicToLatinTrans.transliterate(s);
        }
        return s;
    }

    public static String transliterateGreek(String s) {
        if (containsGreek(s)) {
            final String TRNAME = "Grek-Latn";
            final Transliterator toLatinTrans = Transliterator.getInstance(TRNAME);
            s = toLatinTrans.transliterate(s);
        }
        return s;
    }

    final static Transliterator anyToLatinTrans = Transliterator.getInstance("Any-Latin/BGN");
    public static String transliterateAll(String s) {
        return anyToLatinTrans.transliterate(s);
    }

    public static boolean containsCyrillic(String s) {
        return s.chars()
                .mapToObj(Character.UnicodeBlock::of)
                .anyMatch(Character.UnicodeBlock.CYRILLIC::equals);
    }

    public static boolean containsGreek(String s) {
        return s.chars()
                .mapToObj(Character.UnicodeBlock::of)
                .anyMatch(Character.UnicodeBlock.GREEK::equals);
    }

    public static boolean containsChinese(String s) {
        return s.chars()
                .mapToObj(Character.UnicodeBlock::of)
                .anyMatch(Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS::equals);
    }

    public static String randomAlphanumeric(int n) {

        // chose a Character random from this String
        final String AlphaNumericString = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "0123456789"
                + "abcdefghijklmnopqrstuvxyz";

        StringBuilder sb = new StringBuilder(n);

        for (int i = 0; i < n; i++) {
            int index = (int)(AlphaNumericString.length() * Math.random());
            sb.append(AlphaNumericString.charAt(index));
        }

        return sb.toString();
    }

    public static String getTmpDir() {
        return System.getProperty("java.io.tmpdir");
    }

    // ~/.etext-store
    public static String getAppDotDir() {
        return new File(System.getProperty("user.home"), ".textbase").getAbsolutePath();
    }

    public static Properties readPropertiesFile(InputStream inputStream) {
        final Properties props = new Properties();
        try {
            props.load(new InputStreamReader(inputStream, "UTF-8"));
            return props;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Set<String> readTextFileWithComments(InputStream inputStream) {
        final HashSet<String> result = new HashSet<String>();
        try {
            LineNumberReader reader = new LineNumberReader(
                new InputStreamReader(new BufferedInputStream(inputStream), StandardCharsets.UTF_8)
            );

            String crtLine;
            while ((crtLine = reader.readLine()) != null) {
                String trimmed = crtLine.trim();
                if (trimmed.startsWith("#"))
                    continue;
                result.add(trimmed);
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Properties readPropertiesFileWithComments(InputStream inputStream) {
        final Set<String> strings = readTextFileWithComments(inputStream);
        Set<String[]> splits = strings.stream()
                .map(s -> s.split("="))
                .collect(Collectors.toSet());
        Properties result = new Properties();
        for (String[] s:  splits) {
            result.put(s[0], s[1]);
        }
        return result;
    }

    public static Map<String, Author> readSpecialAuthorsResource() {
        final String resourceName = "special-authors.properties";
        final InputStream resourceAsStream = Author.class.getClassLoader().getResourceAsStream(resourceName);
        final Properties properties = readPropertiesFileWithComments(resourceAsStream);
        final Map<String, Author> result = new HashMap<>();
        for ( String key : properties.stringPropertyNames()) {
            final String value = (String) properties.get(key);
            String[] elems = value.split(";");
            if (elems.length != 4)
                throw new RuntimeException(String.format("a line in file %s should have precisely 4 elements", resourceName));

            int i = 0;

            final String strId          = elems[i++].trim();
            final String firstName      = elems[i++].trim();
            final String lastName       = elems[i++].trim();
            final String displayName    = elems[i++].trim();

            final Author author = Author.builder()
                    .originalNameInTeiFile(displayName)
                    .strId(strId)
                    .firstName(firstName)
                    .lastName(lastName)
                    .displayName(displayName)
                    .build();

            result.put(displayName, author);
        }
        return result;
    }

    public static Set<String> readForbiddenAuthorNames() {
        final InputStream resourceAsStream = Author.class.getClassLoader().getResourceAsStream("forbidden-author-names.txt");
        final Set<String> strings = readTextFileWithComments(resourceAsStream);
        try {
            resourceAsStream.close();
            return strings;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public static Properties readRecommendedAuthorMappings() {
        return readPropertiesFile(Author.class.getClassLoader().getResourceAsStream("recommended-author-urls.properties"));
    }

    public static Properties asProps(Map<String, String> map) {
        final Properties res = new Properties();
        map.forEach((k, v) -> {
            res.setProperty(k, v);
        });
        return res;
    }

    public static UriComponentsBuilder cloneUriComponentBuilder(UriComponentsBuilder uriComponentsBuilder, HttpServletRequest httpServletRequest) {
        final String xforwardedProto = httpServletRequest != null ? httpServletRequest.getHeader("x-forwarded-proto") : null;
        final String scheme = xforwardedProto != null ? xforwardedProto : httpServletRequest.getScheme();

        final UriComponentsBuilder clonedBuilder = uriComponentsBuilder
                .cloneBuilder()
                .scheme(scheme); // works for https too

        return clonedBuilder;
    }

    public static String maxNCharsOf(String str, int n) {
        if (str == null) return null;
        assert n > 0;
        return str.substring(0, Math.min(str.length(), n));
    }

    public static String maxNCharsEllipsis(String str, int n) {
        assert n > 0;
        if (n < str.length()) {
            return maxNCharsOf(str, n) + "...";
        } else
            return str;
    }

    public static byte[] sha256(String string) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hashbytes = digest.digest(string.getBytes(StandardCharsets.UTF_8));
            return hashbytes;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha256Hex(String string) {
        final var hashbytes = sha256(string);
        final String sha256 = bytesToHex(hashbytes);
        return  sha256;
    }

    public static String sha3_256Hex(String string) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA3-256");
            final byte[] hashbytes = digest.digest(
                    string.getBytes(StandardCharsets.UTF_8));
            String sha3Hex = bytesToHex(hashbytes);
            return  sha3Hex;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }


    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (int i = 0; i < hash.length; i++) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if(hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    static public String[] pathFragments(String path) {
        return Arrays.stream(path.split("\\/"))
                .filter(Objects::nonNull)
                .filter(it -> !it.isEmpty())
                .filter(it -> !it.isBlank())
                .toArray(String[]::new);
    }

    public static void assertTrue(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    public static String getExtension(String lastFragm) {
        final var dotIndex = lastFragm.lastIndexOf('.');
        if (dotIndex > 0) {
            return lastFragm.substring(dotIndex + 1).toLowerCase();
        }
        return null;
    }

    /**
     * @return the basename, possible extension eliminated
     */
    public static String basename(String stringWithMaybeExtension) {
        int indexOf = stringWithMaybeExtension.indexOf('.');
        if (indexOf > 0) {
            return stringWithMaybeExtension.substring(0, indexOf);
        } else
            return stringWithMaybeExtension;
    }

    public static String urlEncode(String str) {
        try {
            return URLEncoder.encode(str, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static String urlDecode(String str) {
        try {
            return URLDecoder.decode(str, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static Transformer getTransformer(String xslResource) {
        final URL xsltResource = DivController.class.getClassLoader().getResource(xslResource);
        try {
            return XsltTool.getTransformer(xsltResource.openStream(), xslResource);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Runs task on a throwaway thread and gives up after timeoutSeconds,
     * throwing instead of blocking forever - for calls to dependencies
     * (e.g. the embedder) that should degrade gracefully rather than hang
     * a request when that dependency is slow/unresponsive rather than
     * cleanly down (a plain connection-refused fails fast on its own; a
     * contended/half-alive service does not).
     */
    public static <T> T runWithTimeout(Callable<T> task, int timeoutSeconds) throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(task).get(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    public static final String TEI_DIV = "tei:div";

    /**
     * Destructively prunes a fragment's sub-chapters down to a maximum
     * nesting depth, counted from the requested div itself (depth 0), in
     * the same units as the div's own addressable sub-chapters - i.e. the
     * same parent/child structure TeiElem.getDbChildren() and the URL path
     * structure use (see TeifileParser.parcurge_rec): a structural div
     * with no {@code <head>} of its own (TEI produced by odt-to-tei
     * conversions for a heading level with nothing above it, for
     * instance) never got its own TeiDiv row or path segment at import
     * time, and is likewise transparent here - it doesn't consume a depth
     * level, so its own sub-divs are pruned as if they were direct children
     * of its nearest headed ancestor.
     * <ul>
     *     <li>{@code maxDepth == null}: no pruning at all - the full
     *     sub-chapter tree, all the way down to its leaves, is kept. This
     *     is the default when a request doesn't specify a depth.</li>
     *     <li>{@code maxDepth == 0}: every direct (addressable) sub-chapter
     *     div is removed (and, as a consequence of removing a whole subtree
     *     at once, everything nested inside them too) - only the fragment's
     *     own direct content (its own head/paragraphs/etc, not belonging
     *     to any sub-chapter) survives.</li>
     *     <li>{@code maxDepth == N} (N &gt; 0): addressable sub-chapters up
     *     to N levels below the requested div are kept in full; anything
     *     nested deeper than that is pruned.</li>
     * </ul>
     * so you'd better provide a deep copy or know what you're doing.
     * @param elemInfo . nodeCopy will be changed by this method (unless maxDepth is null)
     */
    public static void pruneDivsBeyondDepth(ElemInfo elemInfo, Integer maxDepth) {
        if (maxDepth == null)
            return;
        pruneDivsBeyondDepth(elemInfo.getNodeCopy(), maxDepth);
    }

    private static void pruneDivsBeyondDepth(Node node, int remainingDepth) {
        final XpathTool xt = new XpathTool(node);
        // TEI_DIV as a bare xpath step selects the child:: axis - direct
        // sub-divs only, not all descendants at any depth.
        final NodeList directSubdivs = xt.applyXpathForNodeSet(TEI_DIV);
        for (int i = 0; i < directSubdivs.getLength(); i++) {
            final Node child = directSubdivs.item(i);
            if (!isAddressableDiv(child)) {
                // transparent structural wrapper (no head of its own) - doesn't
                // consume a depth level, but its own sub-divs still need pruning
                pruneDivsBeyondDepth(child, remainingDepth);
            } else if (remainingDepth <= 0) {
                node.removeChild(child);
            } else {
                pruneDivsBeyondDepth(child, remainingDepth - 1);
            }
        }
    }

    /**
     * mirrors TeifileParser's own import-time rule for whether a div gets
     * its own TeiDiv row (a direct tei:head child with non-blank text) -
     * see {@code TeifileParser.parcurge_rec}'s {@code head == null || head.isEmpty()} check.
     */
    private static boolean isAddressableDiv(Node divNode) {
        final XpathTool xt = new XpathTool(divNode, true);
        final NodeList heads = xt.applyXpathForNodeSet("/tei:div/tei:head");
        if (heads.getLength() < 1)
            return false;
        final String text = heads.item(0).getTextContent();
        return text != null && !text.isBlank();
    }

}
