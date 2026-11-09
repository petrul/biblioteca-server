package ro.editii.scriptorium.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * An old division URL and where it now lives. Recorded by
 * {@link ro.editii.scriptorium.service.RelocationService} when a TEI
 * reimport gives a division another path (its slug changed, it moved, or -
 * {@link Reason#PARENT} - it disappeared and its nearest surviving ancestor
 * stands in). A GET on {@code oldPath} that no longer names a division
 * answers 301 to {@code newPath}. Paths are canonical: a leading slash,
 * no extension - {@code /shakespeare/the_tragedy/act_iv/another}.
 */
@Data
@Entity
@NoArgsConstructor @AllArgsConstructor @Builder
public class Relocation {

    public enum Reason {
        /** The same division (same heading chain, or same text) under a new path. */
        MOVED,
        /** The division is gone; its nearest surviving ancestor replaces it. */
        PARENT,
        /** Entered by hand (or by an earlier version without a reason). */
        MANUAL
    }

    @Id
    @Column(length = 2000)
    String oldPath;

    @Column(length = 2000)
    String newPath;

    @Column(length = 16)
    String reason;

    /** When the reimport that recorded it ran. */
    Instant createdAt;
}
