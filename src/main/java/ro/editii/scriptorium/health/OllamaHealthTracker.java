package ro.editii.scriptorium.health;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Shared across every Ollama-backed dependency (OllamaEmbedder's 3
 * configured models AND EnrichmentService's generation calls) - they
 * all hit the same physical Ollama instance/GPU, so a stuck generate
 * call is just as good evidence the embed endpoint will hang too, and
 * vice versa. One flag, not one per model.
 */
@Component
public class OllamaHealthTracker {

    private final ServiceHealthTracker tracker = new ServiceHealthTracker("Ollama", Duration.ofMinutes(15));

    public boolean isAvailable() {
        return this.tracker.isAvailable();
    }

    public void markUnavailable() {
        this.tracker.markUnavailable();
    }

    public void markAvailable() {
        this.tracker.markAvailable();
    }

    public Duration remainingCooldown() {
        return this.tracker.remainingCooldown();
    }
}
