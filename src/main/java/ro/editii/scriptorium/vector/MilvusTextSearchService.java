package ro.editii.scriptorium.vector;

import io.milvus.response.SearchResultsWrapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.Util;
import ro.editii.scriptorium.search.MilvusHit;
import ro.editii.scriptorium.search.content.ContentResolver;

import java.util.List;

/**
 * just a layer that converts a NatLang text query to a vector and sends
 * the vector the milvus.
 */
@Service
@Log4j2
public class MilvusTextSearchService {

    // bounds the embedder call below: a plain connection-refused fails
    // fast on its own, but a slow/GPU-contended Ollama can otherwise hang
    // a search request for minutes - better to degrade to empty results.
    private static final int EMBED_TIMEOUT_SECONDS = 15;

    final MilvusCollection  milvusCollection;
    final Embedder embedder;
    final ContentResolver contentResolver;
    final VectorSearchAvailability vectorSearchAvailability;

    public MilvusTextSearchService(MilvusCollection milvusCollection, Embedder embedder, ContentResolver contentResolver,
                                    VectorSearchAvailability vectorSearchAvailability) {
        this.milvusCollection = milvusCollection;
        this.embedder = embedder;
        this.contentResolver = contentResolver;
        this.vectorSearchAvailability = vectorSearchAvailability;

        validateModelCollectionCompatible(embedder.modelName(), milvusCollection);
    }

    public List<MilvusHit> search(String textQuery) {
        return this.search(textQuery, 10);
    }

    public List<MilvusHit> search(String textQuery, int topK) {
        if (!this.vectorSearchAvailability.isAvailable())
            return List.of();
        final float[] vector;
        try {
            vector = Util.runWithTimeout(() -> this.embedder.encode(textQuery), EMBED_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("Embedder call failed/timed out ({}) - degrading to empty results for this search.", e.getMessage());
            return List.of();
        }
        return this.search(vector, topK);
    }

    /**
     * by convention, to avoid problems, a milvus collection must include the model name
     * which have been used to construct its vectors, in lowercase and dashes replaced by underscores.
     * as such, all-mini-LV-etc should be a part of the name as: collection_all_mini_lv_etc_suffix_here
     */
    private void validateModelCollectionCompatible(String modelname, MilvusCollection milvusCollection) {
        final var modelName = modelname.toLowerCase().replaceAll("-", "_");
        final var collectionNameLC = milvusCollection.name.toLowerCase().replaceAll("-", "_");
        Util.assertTrue(collectionNameLC.contains(modelName));
    }

    public List<MilvusHit> search(float[] vector) {
        return this.search(vector, 10);
    }

    public List<MilvusHit> search(float[] vector, int topK) {
        if (!this.vectorSearchAvailability.isAvailable())
            return List.of();
        try {
            final SearchResultsWrapper resultsWrapper = this.milvusCollection.search(new float[][] { vector }, topK);
            return VectorUtils.searchResultsWrapperToHits(resultsWrapper, this.contentResolver);
        } catch (RuntimeException e) {
            // Same degradation as the embedder timeout above: a mid-run Milvus
            // outage/restart must turn this one request into empty results,
            // not a raw SDK exception (a 500 to the caller) - the one-shot
            // availability flag can still be true through such an outage,
            // and VectorSearchAvailability's periodic re-check only flips it
            // within its next minute-long interval.
            log.warn("Milvus search failed ({}) - degrading to empty results for this search.", e.getMessage());
            return List.of();
        }
    }



//    static String getTextbaseUrl(String id) {
//        final var fragms = Arrays.stream(Util.pathFragments(id)).toList(); //Arrays.stream(id.split("/")).toList();
//        var last = fragms.get(fragms.size() - 1);
//        last = last.replaceAll("^\\d+\\_\\d+\\_", "");
//        last = last.replaceAll("\\_\\_", "/");
//        last = last.split("#")[0];
//        last = last.replaceAll("\\.txt$", "");
//        return "https://textbase.scriptorium.ro/" + last;
//    }

}
