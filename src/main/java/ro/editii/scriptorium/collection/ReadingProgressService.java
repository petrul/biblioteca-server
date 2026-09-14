package ro.editii.scriptorium.collection;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.editii.scriptorium.dao.ReadingProgressRepository;
import ro.editii.scriptorium.model.AppUser;
import ro.editii.scriptorium.model.ReadingProgress;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.model.TeiElem;
import ro.editii.scriptorium.service.DivService;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReadingProgressService {

    final ReadingProgressRepository readingProgressRepository;
    final DivService divService;

    /**
     * Upserts by (user, opus) - a fresh save always replaces whatever
     * position that user had in that work before, this is never a history.
     */
    @Transactional
    public ReadingProgress save(AppUser user, String divPath) {
        final TeiDiv div = resolveDiv(divPath);
        final TeiDiv opus = div.getOpus();

        final ReadingProgress existing = this.readingProgressRepository
                .findByUserAndOpus(user, opus)
                .orElse(null);

        if (existing != null) {
            // A genuinely new div starts at its own top; re-saving the SAME
            // div (e.g. "Continue Reading" resuming exactly where a reader
            // left off) must not wipe the scroll position they're resuming to.
            if (!existing.getDiv().equals(div)) {
                existing.setScrollFraction(0.0);
            }
            existing.setDiv(div);
            existing.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            existing.setTouchCount(existing.getTouchCount() + 1);
            return this.readingProgressRepository.save(existing);
        }

        return this.readingProgressRepository.save(ReadingProgress.builder()
                .user(user)
                .opus(opus)
                .div(div)
                .build());
    }

    /**
     * Adds to this reader's cumulative time-spent for a work they already
     * have a saved position in (see ReadingProgress.attentionSeconds).
     * Only ever called for signed-in readers - there is no anonymous
     * attention tracking, same as reading progress itself.
     */
    @Transactional
    public ReadingProgress addAttention(AppUser user, String opusPath, long secondsDelta) {
        final TeiDiv opus = resolveDiv(opusPath);
        final ReadingProgress existing = this.readingProgressRepository
                .findByUserAndOpus(user, opus)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no reading progress yet for this work - open a chapter before reporting attention"));

        existing.setAttentionSeconds(existing.getAttentionSeconds() + secondsDelta);
        return this.readingProgressRepository.save(existing);
    }

    /**
     * Updates just the in-chapter scroll position for a work the reader
     * already has a saved position in - reported in small debounced/
     * heartbeat chunks by the reader app while a chapter is open (mirrors
     * addAttention above), only for signed-in readers.
     *
     * divPath must match the div this reader is currently saved on: a
     * late update after they've since moved to a different chapter (a
     * benign race between this and the next save()) is silently ignored
     * rather than clobbering the new chapter's own scroll position.
     */
    @Transactional
    public ReadingProgress updateScrollPosition(AppUser user, String opusPath, String divPath, double scrollFraction) {
        final TeiDiv opus = resolveDiv(opusPath);
        final ReadingProgress existing = this.readingProgressRepository
                .findByUserAndOpus(user, opus)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no reading progress yet for this work - open a chapter before reporting scroll position"));

        if (!existing.getDiv().getCompletePath().equals(divPath)) {
            return existing;
        }

        existing.setScrollFraction(Math.max(0.0, Math.min(1.0, scrollFraction)));
        return this.readingProgressRepository.save(existing);
    }

    @Transactional(readOnly = true)
    public Optional<ReadingProgress> get(AppUser user, String opusPath) {
        final TeiDiv opus = resolveDiv(opusPath);
        return this.readingProgressRepository.findByUserAndOpus(user, opus);
    }

    @Transactional(readOnly = true)
    public List<ReadingProgress> listAll(AppUser user) {
        return this.readingProgressRepository.findByUser(user);
    }

    private TeiDiv resolveDiv(String divPath) {
        final TeiElem elem = this.divService.getByPath(divPath);
        if (!(elem instanceof TeiDiv div))
            throw new IllegalArgumentException("path does not resolve to a div/chapter: " + divPath);
        return div;
    }
}
