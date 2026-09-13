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
            existing.setDiv(div);
            existing.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
            return this.readingProgressRepository.save(existing);
        }

        return this.readingProgressRepository.save(ReadingProgress.builder()
                .user(user)
                .opus(opus)
                .div(div)
                .build());
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
