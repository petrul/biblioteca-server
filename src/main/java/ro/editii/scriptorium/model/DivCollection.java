package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ro.editii.scriptorium.collection.CollectionKind;
import ro.editii.scriptorium.collection.ItemCollection;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * A user-curated grouping of TeiDivs and/or Fragments (see DivCollectionItem) -
 * the user-created counterpart to the "system" collections (by repo, by
 * language, by author), which aren't persisted at all since they're fully
 * computable from existing data (see DivCollectionRestController's /system/*
 * endpoints). Named DivCollection (not Collection) to avoid colliding with
 * java.util.Collection - this codebase imports java.util.* liberally.
 *
 * "favorites" (isFavorites=true) is a normal DivCollection like any other,
 * auto-created once per user at registration (see AppUserRegistrationService)
 * rather than a separately-modeled concept - the (owner, name) unique
 * constraint below is what stops a second collection from also being named
 * "favorites" for the same owner.
 */
@Entity
@Table(name = "book_collection", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "name"}))
@Data
@Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DivCollection implements Serializable, ItemCollection {

    public static final String FAVORITES_NAME = "favorites";
    public static final int NAME_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    UUID id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JsonIgnore
    AppUser owner;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    @Size(max = NAME_MAX_LENGTH)
    String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    CollectionKind kind = CollectionKind.USER;

    // SEARCH collections only: what was searched (the name defaults from it
    // but is user-editable) and the search options as a JSON object.
    @Column(length = 500)
    String searchQuery;

    @Column(length = 4000)
    String searchParams;

    @Builder.Default
    Timestamp createdAt = new Timestamp(new Date().getTime());

    // EAGER, not LAZY: collections are small curated lists (not a dataset
    // to page through), and callers (e.g. DivCollectionRestController's DTO
    // mapping) read .items outside of any @Transactional boundary of their
    // own - lazy would throw LazyInitializationException there.
    @Builder.Default
    @OneToMany(mappedBy = "collection", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sortOrder asc, addedAt asc")
    List<DivCollectionItem> items = new ArrayList<>();

    public boolean isFavorites() {
        return kind == CollectionKind.FAVORITES;
    }
}
