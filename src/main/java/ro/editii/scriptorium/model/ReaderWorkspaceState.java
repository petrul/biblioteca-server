package ro.editii.scriptorium.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.sql.Timestamp;

/** Per-reader, versioned search/workspace state. */
@Entity
@Table(name = "reader_workspace_state", uniqueConstraints = @UniqueConstraint(columnNames = "owner_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReaderWorkspaceState implements Serializable {
    public static final int JSON_MAX_LENGTH = 100_000;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reader_workspace_state_seq")
    @SequenceGenerator(name = "reader_workspace_state_seq", sequenceName = "reader_workspace_state_seq", allocationSize = 50)
    Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    AppUser owner;

    @Column(nullable = false)
    @Builder.Default
    Long revision = 0L;

    @Lob
    @Column(length = JSON_MAX_LENGTH)
    String lastSearch;

    @Lob
    @Column(length = JSON_MAX_LENGTH)
    String workspace;

    @Column(nullable = false)
    Timestamp updatedAt;
}
