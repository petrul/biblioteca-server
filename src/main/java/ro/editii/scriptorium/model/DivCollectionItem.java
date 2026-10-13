package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ro.editii.scriptorium.collection.CollectionEntry;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Date;
import java.util.UUID;

/**
 * One entry in a DivCollection: either a whole TeiDiv (a book, chapter, or
 * subchapter - kind=DIV, fragmentStart/End unused) or an arbitrary
 * quotation within one (kind=FRAGMENT, div is the Fragment's containing div,
 * fragmentStart/End are its dot-number-notation points - see
 * FragmentResolutionService/DotPath). A Fragment itself is never persisted
 * as its own row anywhere else - this is its only stored representation,
 * and it's re-resolved (re-rendered) on read, same as /quote/... does.
 */
@Entity
@Table(name = "book_collection_item")
@Data
@Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DivCollectionItem implements Serializable, CollectionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    UUID id;

    // Excluded from equals/hashCode/toString - it's the back-reference of
    // DivCollection.items, and Lombok's generated methods on both sides
    // would otherwise recurse into each other forever (StackOverflowError).
    @ManyToOne(optional = false)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    DivCollection collection;

    @Enumerated(EnumType.STRING)
    Kind kind;

    // The div's stable path, not a @ManyToOne TeiDiv reference - a
    // re-import assigns every TeiDiv a fresh generated id, which would
    // silently orphan this row (see ReadingProgress.opusPath's own doc
    // comment for the same reasoning).
    String divPath;

    // Only set for kind=FRAGMENT - see class doc. A SNIPPET is a search hit
    // (div + matched excerpt) with no resolvable dot-path range.
    String fragmentStart;
    String fragmentEnd;

    // Search-result entries: the excerpt that matched and its score.
    @Column(length = 2000)
    String snippet;
    Float score;

    // Position inside the collection (user-sortable).
    @Builder.Default
    int sortOrder = 0;

    @Builder.Default
    Timestamp addedAt = new Timestamp(new Date().getTime());

    public enum Kind { DIV, FRAGMENT, SNIPPET }
}
