package ro.editii.scriptorium.media;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @AllArgsConstructor @NoArgsConstructor
@Table(name = "author_media",
        uniqueConstraints = {
    @UniqueConstraint(columnNames = { "author_path", "media_ref" })
})
@Entity
public class AuthorMediaAssociation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "author_media_seq")
    @SequenceGenerator(name = "author_media_seq", sequenceName = "author_media_seq", allocationSize = 50)
    Long id;

    @Column(name="author_path", length = 1000)
    String authorPath; // we'll identify a div by its path

    @ManyToOne
    @JoinColumn(name = "media_ref", referencedColumnName = "url", columnDefinition = "VARCHAR(500)")
    MediaRef mediaRef;
}
