package ro.editii.scriptorium.config;

/** A safe-to-serialise view of one runtime configuration value. */
public record RuntimeConfigEntry(
        String key,
        String environmentVariable,
        String value,
        boolean configured,
        boolean sensitive,
        boolean restartRequired) {
}
