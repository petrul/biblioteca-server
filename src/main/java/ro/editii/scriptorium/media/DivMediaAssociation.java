package ro.editii.scriptorium.media;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
@Table(name = "div_media",
        uniqueConstraints = {
    @UniqueConstraint(columnNames = { "div_path", "media_ref" })
})
@Entity
public class DivMediaAssociation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "div_media_seq")
    @SequenceGenerator(name = "div_media_seq", sequenceName = "div_media_seq", allocationSize = 50)
    Long id;

    @Column(name="div_path", length = 1000)
    String divPath; // we'll identify a div by its path

    @ManyToOne
    @JoinColumn(name = "media_ref", referencedColumnName = "url", columnDefinition = "VARCHAR(500)")
    MediaRef mediaRef;
}
