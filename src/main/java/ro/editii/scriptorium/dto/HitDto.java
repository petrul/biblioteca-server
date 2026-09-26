package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.util.UriComponentsBuilder;
import ro.editii.scriptorium.model.Author;
import ro.editii.scriptorium.model.TeiDiv;
import ro.editii.scriptorium.search.GrepHit;
import ro.editii.scriptorium.search.LuceneHit;
import ro.editii.scriptorium.search.VectorHit;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data @AllArgsConstructor @NoArgsConstructor @Builder
public class HitDto implements Dto {

    String type; // author, div, milvus etc
    String url; // identifies the hit address
    Float score;
    String content; // text or excerpt illustrating the hit
    Object data; // author or div or smth else

    public enum TYPES { author, div, milvus, lucene, grep }

    public static HitDto from(Author author, UriComponentsBuilder uriComponentsBuilder) {
        return HitDto.builder()
                .type(TYPES.author.name())
                .url(author.getUrl(uriComponentsBuilder))
                .data(AuthorDto.from(author))
                .score(null)
                .build();
    }

    public static HitDto from(TeiDiv it, UriComponentsBuilder ucb) {
        return HitDto.builder()
                .type(TYPES.div.name())
                .url(it.getCompletePath())
                .data(TeiDivDto.fromTeiDiv(it, ucb))
                .build();
    }

    public static HitDto from(VectorHit hit
//            , UriComponentsBuilder uriComponentsBuilder
    ) {
        return HitDto.builder()
                .type(TYPES.milvus.name())
                .url(hit.getUrl())
                .score(hit.getScore())
                .content(hit.getContent())
                .build();
    }

    public static HitDto from(LuceneHit hit) {
        return HitDto.builder()
                .type(TYPES.lucene.name())
                .url(hit.getUrl())
                .score(hit.getScore())
                .content(hit.getContent())
                .build();
    }

    public static HitDto from(GrepHit hit) {
        return HitDto.builder()
                .type(TYPES.grep.name())
                .url(hit.getUrl())
                .content(hit.getContent())
                .build();
    }


}
