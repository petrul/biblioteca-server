package ro.editii.scriptorium.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.editii.scriptorium.config.RuntimeConfigEntry;
import ro.editii.scriptorium.config.RuntimeConfigService;
import ro.editii.scriptorium.config.RuntimeConfigUpdate;

import java.util.List;

/** Admin-only runtime configuration; values live only for this JVM process. */
@RestController
@RequestMapping("/api/admin/runtime-config")
@RequiredArgsConstructor
public class RuntimeConfigRestController {
    private final RuntimeConfigService runtimeConfigService;

    @GetMapping
    public List<RuntimeConfigEntry> list(@RequestParam(defaultValue = "false") boolean reveal) {
        return runtimeConfigService.snapshot(reveal);
    }

    @GetMapping("/{key}")
    public RuntimeConfigEntry get(@PathVariable String key,
                                  @RequestParam(defaultValue = "false") boolean reveal) {
        return runtimeConfigService.get(key, reveal);
    }

    @PutMapping("/{key}")
    public RuntimeConfigEntry set(@PathVariable String key,
                                  @RequestBody RuntimeConfigUpdate update,
                                  @RequestParam(defaultValue = "false") boolean reveal) {
        return runtimeConfigService.set(key, update.value(), reveal);
    }

    @DeleteMapping("/{key}")
    public RuntimeConfigEntry reset(@PathVariable String key,
                                    @RequestParam(defaultValue = "false") boolean reveal) {
        return runtimeConfigService.reset(key, reveal);
    }
}
