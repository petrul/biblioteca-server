package ro.editii.scriptorium.vector;

import io.milvus.response.SearchResultsWrapper;
import org.jetbrains.annotations.NotNull;
import ro.editii.scriptorium.search.VectorHit;
import ro.editii.scriptorium.search.content.ContentResolver;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class VectorUtils {

    @NotNull
    public static List<List<Float>> arr2List(float[][] vectors) {
        return Arrays.stream(vectors)
                .map(vect -> arr2List(vect))
                .toList();
    }

    public static List<Float> arr2List(float[] vector) {
        return IntStream
                .range(0, vector.length)
                .mapToObj(it -> Float.valueOf(vector[it]))
                .toList();
    }

    public static List<VectorHit> searchResultsWrapperToHits(SearchResultsWrapper resultsWrapper, ContentResolver contentResolver) {
        final List<SearchResultsWrapper.IDScore> scores = resultsWrapper.getIDScore(0);
        final List<VectorHit> milvusHits = getVectorHits(scores, contentResolver);
        return milvusHits;
    }

    /**
     * Store-agnostic counterparts of the wrapper-based conversions above
     * (see VectorCollection/QdrantCollection): a store returns neutral
     * VectorSearchHits, and the search services resolve them into the
     * same VectorHit the SDK-typed path always produced.
     */
    public static List<VectorSearchHit> searchResultsWrapperToSearchHits(SearchResultsWrapper resultsWrapper) {
        return resultsWrapper.getIDScore(0).stream()
                .map(row -> new VectorSearchHit(
                        (String) row.get(MilvusCollection.FIELD_SHA_256),
                        (String) row.get(MilvusCollection.FIELD_URL),
                        row.getScore()))
                .toList();
    }

    public static List<VectorHit> searchHitsToHits(List<VectorSearchHit> hits, ContentResolver contentResolver) {
        return hits.stream()
                .map(hit -> VectorHit.from(hit.url(), hit.score(), contentResolver.resolve(hit.url())))
                .toList();
    }

    @NotNull
    private static List<VectorHit> getVectorHits(List<SearchResultsWrapper.IDScore> scores, ContentResolver contentResolver) {
        final var resp  = scores.stream()
                .map(it -> {
//                    final var sid = (String) it.get(MilvusCollection.FIELD_SHA_256);
                    final var url = (String) it.get(MilvusCollection.FIELD_URL);
                    final var cnt = contentResolver.resolve(url);
                    return VectorHit.from(url, it.getScore(), cnt);
                })
                .toList();
        return resp;
    }


}
