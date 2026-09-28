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

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name="author_path", length = 1000)
    String authorPath; // we'll identify a div by its path

    @ManyToOne
    @JoinColumn(name = "media_ref", referencedColumnName = "url", columnDefinition = "VARCHAR(500)")
    MediaRef mediaRef;
}
