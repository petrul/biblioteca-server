package ro.editii.scriptorium.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "name"}))
@Data
@Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DivCollection implements Serializable {

    public static final String FAVORITES_NAME = "favorites";
    public static final int NAME_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "div_collection_seq")
    @SequenceGenerator(name = "div_collection_seq", sequenceName = "div_collection_seq", allocationSize = 50)
    Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JsonIgnore
    AppUser owner;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    @Size(max = NAME_MAX_LENGTH)
    String name;

    @Builder.Default
    boolean isFavorites = false;

    @Builder.Default
    Timestamp createdAt = new Timestamp(new Date().getTime());

    // EAGER, not LAZY: collections are small curated lists (not a dataset
    // to page through), and callers (e.g. DivCollectionRestController's DTO
    // mapping) read .items outside of any @Transactional boundary of their
    // own - lazy would throw LazyInitializationException there.
    @Builder.Default
    @OneToMany(mappedBy = "collection", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("addedAt asc")
    List<DivCollectionItem> items = new ArrayList<>();
}
