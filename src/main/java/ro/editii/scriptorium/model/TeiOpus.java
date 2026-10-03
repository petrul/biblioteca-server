package ro.editii.scriptorium.model;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Work-level metadata.  A TeiDiv is the parsed tree node; this entity is the
 * stable, editable envelope around a root-level opus.  Enrichment and manual
 * edits only fill blank values and never alter the parsed TEI tree.
 */
@Entity
@Table(name = "TEI_OPUS", uniqueConstraints = @UniqueConstraint(name = "UK_TEI_OPUS_DIV", columnNames = "TEI_DIV_ID"))
@Getter @Setter @NoArgsConstructor
public class TeiOpus implements Serializable {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "TEI_DIV_ID", nullable = false)
    private TeiDiv teiDiv;

    /** Reader-facing description; initialized from the opening TEI paragraphs. */
    @Column(length = 1200)
    private String description;

    /** Short representative quotation used by cards and cover generation. */
    @Column(name = "SIGNIFICANT_QUOTE", length = 1200)
    private String significantQuote;

    /** URL-only generated cover pointer; image bytes never live in the DB. */
    @Column(length = 1000)
    private String coverUrl;

    public TeiOpus(TeiDiv teiDiv, String description) {
        this.teiDiv = teiDiv;
        this.description = description;
        teiDiv.setOpusMetadata(this);
    }
}
