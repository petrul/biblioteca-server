package ro.editii.scriptorium.rest;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ro.editii.scriptorium.service.RelocationService;

import java.time.Instant;

/**
 * Where a relocated division path lives now - for clients that navigate by
 * path without fetching the old page itself (the reader, on a favourite or a
 * cached link). The pages and /api/divs?path= answer an old path with a 301.
 */
@RestController
@RequestMapping("/api/relocations")
@RequiredArgsConstructor
public class RelocationRestController {

    final RelocationService relocationService;

    public record RelocationDto(String oldPath, String newPath, String reason, Instant createdAt) {}

    @Operation(operationId = "getRelocation",
            description = "the current path of a division path that a TEI reimport changed (relocation chains followed), " +
                    "e.g. /shakespeare/the_tragedy/act_iv/another_room_in_the_castle; 404 when it was not relocated")
    @GetMapping("")
    public RelocationDto get(@RequestParam("path") String path) {
        return this.relocationService.resolve(path)
                .map(r -> new RelocationDto(r.getOldPath(), r.getNewPath(), r.getReason(), r.getCreatedAt()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not relocated: " + path));
    }
}
