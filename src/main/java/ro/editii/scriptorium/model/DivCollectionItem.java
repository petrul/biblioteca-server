package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Date;

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
@Data
@Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DivCollectionItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

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

    // Only set for kind=FRAGMENT - see class doc.
    String fragmentStart;
    String fragmentEnd;

    @Builder.Default
    Timestamp addedAt = new Timestamp(new Date().getTime());

    public enum Kind { DIV, FRAGMENT }
}
