package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.Util;

import java.io.Serializable;
import java.sql.Blob;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@Data
@Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Author implements Comparable<Author>, Serializable {

    final public static String ANONYMOUS_AUTHOR = "[ANON]"; // the bible for ex.
    public static final int NAME_COL_MAX_LENGTH = 255;

    // mapping  caragiale => Caragiale,Ion-Luca
    public static Properties RECOMMENDED_AUTHOR_MAPPINGS;

    // reverse mapping : Caragiale,Ion-Luca=>caragiale
    public static Properties RECOMMENDED_AUTHOR_MAPPINGS_REV = new Properties();

    // 'Regina Maria a României' => Author(strId = 'regina_maria' ...)
    public static Map<String, Author> SPECIAL_AUTHORS; // kings and such

    public static Set<String> FORBIDDEN_AUTHOR_NAMES = new HashSet<>();
    static {
        RECOMMENDED_AUTHOR_MAPPINGS = Util.readRecommendedAuthorMappings();
        RECOMMENDED_AUTHOR_MAPPINGS.keySet().forEach( (key) -> {
            final String skey = (String) key;
            final String value = RECOMMENDED_AUTHOR_MAPPINGS.getProperty(skey);
            RECOMMENDED_AUTHOR_MAPPINGS_REV.setProperty(value, skey);
        });

        FORBIDDEN_AUTHOR_NAMES = Util.readForbiddenAuthorNames();
        SPECIAL_AUTHORS = Util.readSpecialAuthorsResource();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    /**
     * i.e. balcescu, should be all-lowercase, no-accent version of lastName
     */
    @Column(unique = true)
    String strId;

    /**
     * e.g. Alecsandri
     */
    @EqualsAndHashCode.Include
    @Column(length = NAME_COL_MAX_LENGTH)
    protected String lastName;

    /**
     * e.g. Vasile
     */
    @EqualsAndHashCode.Include
    @Column(length = NAME_COL_MAX_LENGTH)
    protected String firstName;

    /**
     * e.g. "Alecsandri,Vasile". the file name as in the TEI. maybe useful for identifying authors
     * that have already been inserted
     */
    @JsonIgnore
    @Column(unique = true, length = NAME_COL_MAX_LENGTH)
    String originalNameInTeiFile;

    // prefered display name; if null, computed from firstName and lastName by method getVisualName()
    String displayName;

//    String description; // remove

    @Lob @ToString.Exclude
    Blob avatar;

    // Auto-generated (AiEnrichmentService, async after import) - a short
    // biography synthesized from a trusted source (Wikipedia's own REST
    // summary API when this author has a page there, else the search
    // engine's own snippets as a fallback - see enrichAuthorAsync). Null
    // until enrichment has actually run once; never overwritten by a
    // later reimport once set, so a manual edit here survives.
    @Lob @ToString.Exclude
    String bio;

    // The page bio was derived from (a Wikipedia article, or whatever
    // search result was used as the fallback source) - shown alongside
    // bio as attribution/further reading, not just internal provenance.
    @Column(length = 500)
    String bioSourceUrl;

    // Most authors write in exactly one language - backfilled
    // opportunistically (AiEnrichmentService) from whichever opus of
    // theirs happens to get imported/enriched first, since nothing
    // detects this independently yet. Drives which language the
    // enrichment search itself runs in (a native-language query finds
    // much better source material than an English one - see the
    // Skala Eresou/Eminescu Greek-vs-English search comparison this was
    // based on).
    @Enumerated(EnumType.STRING)
    Languages nativeLanguage;

    // Auto-generated alongside bio (AiEnrichmentService, same call, same
    // "only ever set once" one-shot rule) - free-text rather than a
    // parsed date, since the source material often only gives an
    // approximate or partial date (a bare year, "c. 1837") and a strict
    // date type would just reject those instead of storing them.
    @Column(length = 100)
    String birthDate;

    @Column(length = 100)
    String deathDate;

    @Column(length = 255)
    String birthPlace;

    // The language the author actually wrote in - usually the same as
    // nativeLanguage, but not always (a bilingual author, someone who
    // wrote mainly in a learned/literary language other than their
    // mother tongue). Auto-generated alongside bio, same as the fields
    // above.
    @Enumerated(EnumType.STRING)
    Languages writingLanguage;

    @Column(length = 255)
    String country;


    /**
     * how to parse an originalNameInTeiFile : e.g. Alecsandri,Vasile.
     *
     * - First, should be the name, than the first name.
     * - for one name only, lastname gets it, first name stands empty
     * - for several commas (should never happen, first element goes as last name, the rest are reconcatenated using
     * blanks and go into the first name
     *
     * @param originalNameInTeiFile
     */
    public static Author newFromOriginalNameInTeiFile(String originalNameInTeiFile) {
        assert originalNameInTeiFile != null;

        originalNameInTeiFile = originalNameInTeiFile.trim();

        if (Author.SPECIAL_AUTHORS.containsKey(originalNameInTeiFile))
            return Author.SPECIAL_AUTHORS.get(originalNameInTeiFile);

        final Author author = new Author();

        List<String> strings;
        if (originalNameInTeiFile.contains(",")) {
            strings = Arrays.asList(originalNameInTeiFile.split(","))
                    .stream()
                    .map(String::trim)
                    .collect(Collectors.toList());
        } else {
            if (originalNameInTeiFile.contains(" ")) {
                strings = Arrays.asList(originalNameInTeiFile.split("\\s+"))
                        .stream()
                        .map(String::trim)
                        .collect(Collectors.toList());
                Collections.reverse(strings);
            } else {
                strings = new ArrayList<>();
                strings.add(originalNameInTeiFile);
            }
        }

        if (strings.size() < 1) {
            // the Bible and such
            author.setLastName(ANONYMOUS_AUTHOR);
        }

        String strings_0_trimmed = strings.get(0);
        if (strings.size() == 2) { // most common case
            author.setLastName(strings_0_trimmed);
            if (author.getLastName().equals(ANONYMOUS_AUTHOR))
                throw new IllegalArgumentException("last name of author should not be " + ANONYMOUS_AUTHOR);
            author.setFirstName(strings.get(1));

        } else
        if (strings.size() == 1) {
            // famous 1-named like "Plato"
            if (strings_0_trimmed.equals(ANONYMOUS_AUTHOR)
                    ||
                "NONE".equals(strings_0_trimmed.toUpperCase()))
                author.setLastName(ANONYMOUS_AUTHOR);
            else
                author.setLastName(strings_0_trimmed);
        } else {
            // more than two names: first is last, the rest are joined as composed first name
            author.setLastName(strings_0_trimmed);
            List<String> subarr = strings.subList(1, strings.size());
            author.setFirstName(String.join(" ", subarr).trim());
        }

        author.setOriginalNameInTeiFile(recomposeOriginalFromParsedAuthor(author));

        return author;
    }


    protected static String recomposeOriginalFromParsedAuthor(Author author) {
        if (author.isAnonymous() || author.isOneNamed())
            return author.getLastName();
        else
            return author.getLastName() + "," + author.getFirstName();
    }

    /**
     * @return displayName if non-null, else computes visually appealing name from first and last names.
     */
    public String getVisualName() {
        if (this.displayName != null)
            return this.displayName;

        if (firstName == null)
            return this.lastName;
        else
            return this.firstName + " " + this.lastName;
    }

    public boolean isAnonymous() {
        return ANONYMOUS_AUTHOR.equals(this.lastName);
    }

    @JsonIgnore
    public boolean isOneNamed() {
        return this.lastName != null && this.lastName.length() > 0 && this.firstName == null;
    }

    @JsonIgnore
    public boolean isTwoNamed() {
        return this.lastName != null && this.lastName.length() > 0
                && this.firstName != null && this.firstName.length() > 0;
    }


    @Override
    public int compareTo(Author other) {
        final String thisLastName = this.getLastName();
        final String thatLastName = other.getLastName();

        if (thisLastName == null || thatLastName == null)
            return 0;

        int result = thisLastName.compareTo(thatLastName);
        if (result != 0) return result;

        final String thisFirstName = this.getFirstName();
        final String thatFirstName = other.getFirstName();

        if (thisFirstName == null || thatFirstName == null)
            return 0;
        result = thisFirstName.compareTo(thatFirstName);
        return result;
    }

    public void setLastName(String lastName) {
        this.lastName = Util.maxNCharsOf(lastName, NAME_COL_MAX_LENGTH);
    }

    public void setFirstName(String firstName) {
        this.firstName = Util.maxNCharsOf(firstName, NAME_COL_MAX_LENGTH);
    }

    public void setOriginalNameInTeiFile(String str) {
        this.originalNameInTeiFile = Util.maxNCharsOf(str, NAME_COL_MAX_LENGTH);
    }

    public String getUrl(UriComponentsBuilder ucb) {
        return ucb.path(this.getStrId()).build().toUriString();
    }
}
