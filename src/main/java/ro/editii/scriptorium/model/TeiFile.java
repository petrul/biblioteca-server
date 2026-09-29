package ro.editii.scriptorium.model;

import jakarta.validation.constraints.Size;
import lombok.Data;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

/**
 * represents a TEI xml file
 */
@Entity
@Data @ToString(onlyExplicitlyIncluded = true)
public class TeiFile implements Serializable, Comparable<TeiFile> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tei_file_seq")
    @SequenceGenerator(name = "tei_file_seq", sequenceName = "tei_file_seq", allocationSize = 50)
    Long id;

    @Column(unique = true) @ToString.Include
    String filename;

    @Column(length = 1000)
    @Size(max=1000)
    String title;

    @EqualsAndHashCode.Exclude
    @ManyToMany(fetch = FetchType.EAGER)
    List<Author> authors;

    // Detected once at import time from the document's own text (see
    // LanguageDetectionService), not a per-div value - TeiElem.lang is set
    // to this same value for every div in the file (see TeifileParser),
    // it's not independently detected per paragraph/chapter.
    @Enumerated(EnumType.STRING)
    Languages language;

    // Which named sub-repo (see TeiRepo.getRepoNameForFile) this file was
    // imported from - populated at import time in TeiFileDbService, drives
    // the "by repo" system collection (see DivCollectionRestController).
    @Column(length = 200)
    String repoName;

    Timestamp timestamp = new Timestamp(new Date().getTime());

    public Author getAuthor() {
        if (this.authors.size() != 1)
            throw new IllegalStateException(String.format("expected one author, have %d instead", this.authors.size()));
        return this.authors.iterator().next();
    }

    @Override
    public int compareTo(@NotNull TeiFile that) {
        return this.getFilename().compareTo(that.getFilename());
    }
}
