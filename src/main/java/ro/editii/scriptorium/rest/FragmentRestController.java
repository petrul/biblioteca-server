package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ro.editii.scriptorium.fragment.FragmentSelector;
import ro.editii.scriptorium.fragment.UnifiedFragmentService;

@RestController
@RequestMapping("/api/fragments")
@RequiredArgsConstructor
public class FragmentRestController {
    final UnifiedFragmentService fragments;

    @GetMapping
    public UnifiedFragmentService.Fragment resolve(@RequestParam String path,
            @RequestParam(required = false) String selector, @RequestParam(required = false) String rev) {
        return fragments.resolve(path, selector, rev);
    }

    public record Selection(String path, String selector, String start, String end, String rev) {}

    @PostMapping("/resolve")
    public UnifiedFragmentService.Fragment selection(@RequestBody Selection selection) {
        String selector = selection.selector();
        if (selector == null && selection.start() != null && selection.end() != null)
            selector = FragmentSelector.fromLegacy(selection.start(), selection.end()).toString();
        if (selector == null) throw new IllegalArgumentException("An exact source selection is required");
        return fragments.resolve(selection.path(), selector, selection.rev());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public java.util.Map<String, String> invalid(IllegalArgumentException exception) {
        return java.util.Map.of("error", exception.getMessage());
    }

    @ExceptionHandler(org.springframework.data.rest.webmvc.ResourceNotFoundException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public java.util.Map<String, String> missing(RuntimeException exception) {
        return java.util.Map.of("error", "Fragment source not found");
    }
}
