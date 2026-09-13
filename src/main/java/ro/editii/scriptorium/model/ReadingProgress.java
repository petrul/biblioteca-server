package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
 * One reader's current position within one work ("bookmark"): which div
 * (usually a leaf chapter/section) they last had open. One row per
 * (user, opus) - a fresh save replaces whatever position was there before,
 * this is never a history.
 */
@Entity
@Data
@Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "opus_id"}))
public class ReadingProgress implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(optional = false)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    AppUser user;

    // The work's own root div - a stable per-work key, resolved from
    // TeiElem.getOpus() at save time so callers only ever need to know the
    // specific div they're currently on, not its opus separately.
    @ManyToOne(optional = false)
    TeiDiv opus;

    // The specific division the reader is currently on - not necessarily a
    // leaf (nothing here requires that), just whatever div they last had open.
    @ManyToOne(optional = false)
    TeiDiv div;

    @Builder.Default
    Timestamp updatedAt = new Timestamp(new Date().getTime());
}
