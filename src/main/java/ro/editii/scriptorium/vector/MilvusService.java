package ro.editii.scriptorium.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.R;
import io.milvus.param.collection.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.editii.scriptorium.search.content.NoContentResolver;

/**
 * searches for vectors
 */
@Service
@RequiredArgsConstructor
public class MilvusService {

    final MilvusServiceClient milvus;

    public void loadCollection(String name) {
        this.milvus.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(name)
                        .build()
        );
    }

    public R<GetCollectionStatisticsResponse> getCollectionStatistics(String collection) {
        return milvus.getCollectionStatistics(GetCollectionStatisticsParam.newBuilder()
                .withCollectionName(collection)
                .build());
    }

    public R<ShowCollectionsResponse> getCollectionInfo(String name) {
        R<ShowCollectionsResponse> respShowCollections = this.milvus.showCollections(
                ShowCollectionsParam.newBuilder()
                        .addCollectionName(name)
                        .build()
        );
        return respShowCollections;
    }

    public void dropCollection(String colname) {
        this.milvus.dropCollection(DropCollectionParam.newBuilder()
            .withCollectionName(colname)
            .build());
    }

    public boolean has(String colName) {
        R<Boolean> respHasCollection = this.milvus.hasCollection(
                HasCollectionParam.newBuilder()
                        .withCollectionName(colName)
                        .build()
        );
        if (respHasCollection == null || respHasCollection.getData() == null) {
            final String message = respHasCollection == null
                    ? "no response"
                    : (respHasCollection.getMessage() == null ? "unknown Milvus error" : respHasCollection.getMessage());
            throw new IllegalStateException("Milvus hasCollection failed for '" + colName + "': " + message);
        }
        return respHasCollection.getData();
    }

    public MilvusCollection getAt(String name) {
        return new MilvusCollection(this, name);
    }

    @Override
    public String toString() {
        return "MilvusService{" +
                "milvus=" + milvus.toString() +
                '}';
    }
}
