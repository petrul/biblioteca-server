package ro.editii.scriptorium.service;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.model.Languages;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Detects a TEI document's language from its own text at import time (see
 * TeiFileDbService.importTeiFile) - this is what replaced guessing the
 * language from the file's directory path (TeiDirRepoImpl.getLanguageHint),
 * which silently returned null for anything not filed under a
 * language-coded folder and had no relationship to the document's actual
 * content.
 *
 * Lingua ships its own per-language n-gram models rather than calling a
 * detection library not built for the JVM (e.g. Google's cld/cld3, which
 * would need a native/JNI binding) - one dependency, no external service,
 * no native library to build/ship.
 */
@Service
@Log4j2
public class LanguageDetectionService {

    // Long documents don't need to be fed in full for reliable detection - a
    // representative prefix is enough, and keeps this fast even for a
    // large TEI file (some are whole books).
    private static final int SAMPLE_CHARS = 3000;

    // Below this much plain prose the sample is label-like noise, not a
    // document's language - "XI." alone gets a confident Latin call.
    // Return empty and let the directory hint decide instead.
    private static final int MIN_DETECT_CHARS = 80;

    private final LanguageDetector detector;
    private final Map<Language, Languages> linguaToOurs;

    public LanguageDetectionService() {
        final Map<Language, Languages> mapping = new EnumMap<>(Language.class);
        for (Languages ours : Languages.values()) {
            try {
                final Language lingua = Language.valueOf(ours.getEnName().toUpperCase());
                mapping.put(lingua, ours);
            } catch (IllegalArgumentException e) {
                // Lingua has no model for this one (currently Breton and
                // Norwegian) - detect() below will just never return it.
                // Note Latin IS modeled: a Latin-looking sample gets a
                // confident LA call, which is why detect() samples the
                // document's prose, not its header (see below).
                log.info("No Lingua language model for {} ({}) - it will never be auto-detected.",
                        ours, ours.getEnName());
            }
        }
        this.linguaToOurs = mapping;
        this.detector = LanguageDetectorBuilder.fromLanguages(mapping.keySet().toArray(new Language[0])).build();
    }

    /**
     * @param rawTeiXml the document's raw TEI XML (tags are stripped with a
     *                  cheap regex rather than a real parse - a bit of
     *                  markup noise doesn't meaningfully affect detection on
     *                  a document-sized sample)
     * @return the detected language, or empty if the text is too short/
     * ambiguous for a confident call, or its detected language isn't one
     * this app models at all (see Languages)
     */
    public Optional<Languages> detect(String rawTeiXml) {
        if (rawTeiXml == null || rawTeiXml.isBlank())
            return Optional.empty();

        // Sample the document's own PROSE, not the whole raw file: the
        // teiHeader is markup-heavy boilerplate (titles, bare roman
        // labels like "XI.", publisher and source metadata) that can
        // fill the whole window and - being label-like - Lingua reads it
        // as Latin with full confidence. That is how whole Hungarian
        // books under /hu/ were assigned LA (krudy/bukfenc): detection
        // "succeeded" on the header, so the directory hint never ran.
        final int bodyStart = rawTeiXml.indexOf("</teiHeader>");
        final String prose = bodyStart >= 0 ? rawTeiXml.substring(bodyStart) : rawTeiXml;

        final String plain = prose.replaceAll("<[^>]+>", " ");
        final String sample = plain.length() > SAMPLE_CHARS ? plain.substring(0, SAMPLE_CHARS) : plain;
        // Too little text to trust content over the directory hint: a
        // bare label still gets a confident-but-wrong call ("XI." alone
        // detects as Latin), so short samples defer to the path hint.
        if (sample.strip().length() < MIN_DETECT_CHARS)
            return Optional.empty();

        final Language detected = this.detector.detectLanguageOf(sample);
        if (detected == Language.UNKNOWN)
            return Optional.empty();

        return Optional.ofNullable(this.linguaToOurs.get(detected));
    }
}
