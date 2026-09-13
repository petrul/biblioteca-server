package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ro.editii.scriptorium.collection.ReadingProgressService;
import ro.editii.scriptorium.dao.AppUserRepository;
import ro.editii.scriptorium.dto.ReadingProgressDto;
import ro.editii.scriptorium.model.AppUser;

import java.util.List;

/**
 * One reader's current position ("bookmark") per work - see
 * ReadingProgress/ReadingProgressService. Always scoped to the
 * authenticated caller (SecurityConfig requires auth on this whole path) -
 * there is no anonymous/public reading-progress concept server-side;
 * that's what the reader app's own localStorage-backed BookmarkStore is
 * for, until a reader signs in.
 */
@RestController
@RequestMapping("/api/reading-progress")
@CrossOrigin
@RequiredArgsConstructor
public class ReadingProgressRestController {

    final ReadingProgressService readingProgressService;
    final AppUserRepository appUserRepository;

    private AppUser currentUser(Authentication authentication) {
        return this.appUserRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated as '" + authentication.getName() + "' but no matching AppUser row"));
    }

    @Value
    public static class SaveRequest {
        String divPath;
    }

    /** Every work this reader has any saved position in. */
    @GetMapping
    public List<ReadingProgressDto> mine(Authentication authentication) {
        return this.readingProgressService.listAll(currentUser(authentication)).stream()
                .map(ReadingProgressDto::from)
                .toList();
    }

    /** This reader's saved position in one specific work, if any. */
    @GetMapping("/one")
    public ReadingProgressDto one(Authentication authentication, @RequestParam String opusPath) {
        try {
            return this.readingProgressService.get(currentUser(authentication), opusPath)
                    .map(ReadingProgressDto::from)
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            RestUtil.throw400(e.getMessage());
            return null;
        }
    }

    /** Upserts this reader's position - the div they're currently on. */
    @PutMapping
    public ReadingProgressDto save(Authentication authentication, @RequestBody SaveRequest request) {
        try {
            return ReadingProgressDto.from(
                    this.readingProgressService.save(currentUser(authentication), request.getDivPath()));
        } catch (IllegalArgumentException e) {
            RestUtil.throw400(e.getMessage());
            return null;
        }
    }
}
