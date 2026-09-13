package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import ro.editii.scriptorium.model.ReadingProgress;

import java.sql.Timestamp;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReadingProgressDto {
    String opusPath;
    String divPath;
    String divHead;
    Timestamp updatedAt;

    public static ReadingProgressDto from(ReadingProgress progress) {
        return ReadingProgressDto.builder()
                .opusPath(progress.getOpus().getCompletePath())
                .divPath(progress.getDiv().getCompletePath())
                .divHead(progress.getDiv().getHead())
                .updatedAt(progress.getUpdatedAt())
                .build();
    }
}
