package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * The one preferences document of one signed-in reader: a single arbitrary
 * JSON object stored verbatim, so whatever keys the reader's
 * PreferencesStore carries today - or grows tomorrow - lives in this one
 * value and the schema never changes again (see
 * UserPreferencesRestController, the only consumer). The username (not an
 * AppUser id) keys it, mirroring how reading progress and collections
 * scope themselves to the authenticated principal's own name.
 */
@Entity
@Data
@Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Table(name = "user_preference",
        uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class UserPreference implements Serializable {

    public static final int USERNAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_preference_seq")
    @SequenceGenerator(name = "user_preference_seq", sequenceName = "user_preference_seq", allocationSize = 50)
    Long id;

    @Column(nullable = false, unique = true, length = USERNAME_MAX_LENGTH)
    String username;

    /** The whole preferences document as JSON text - arbitrary, evolving shape. */
    @Lob
    @Column(nullable = false)
    String preferences;
}
