package ro.editii.scriptorium.rest;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.editii.scriptorium.security.GuestNames;

/** Guest names for readers who are not signed in - see {@link GuestNames}. */
@RestController
@RequestMapping("/api/guests")
@RequiredArgsConstructor
public class GuestRestController {

    final GuestNames guestNames;

    public record GuestName(String name) {
    }

    @PostMapping
    @Operation(summary = "A fresh guest name, like guest_formidable_dictator",
            description = "The reader's browser asks for one on its first visit and keeps it, so a guest's "
                    + "browsing reads under one name in every Biblioteca service's log.")
    public GuestName newGuestName() {
        return new GuestName(this.guestNames.newName());
    }
}
