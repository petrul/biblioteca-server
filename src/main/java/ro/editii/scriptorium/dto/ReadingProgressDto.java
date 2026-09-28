package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import ro.editii.scriptorium.model.ReadingProgress;

import java.sql.Timestamp;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReadingProgressDto {

    // Engagement threshold: a work counts as "read" once its reading
    // progress has been saved this many times (see ReadingProgress.touchCount).
    private static final int READ_THRESHOLD_TOUCHES = 3;

    String opusPath;
    String divPath;
    String divHead;
    Timestamp updatedAt;
    int touchCount;
    boolean read;
    long attentionSeconds;
    double scrollFraction;

    // divHead is resolved by the caller (ReadingProgressService.toDto) -
    // opusPath/divPath are the entity's own stored fields now, but the
    // head text isn't stored (it can change independently of the path),
    // so it always needs a fresh lookup.
    public static ReadingProgressDto from(ReadingProgress progress, String divHead) {
        return ReadingProgressDto.builder()
                .opusPath(progress.getOpusPath())
                .divPath(progress.getDivPath())
                .divHead(divHead)
                .updatedAt(progress.getUpdatedAt())
                .touchCount(progress.getTouchCount())
                .read(progress.getTouchCount() >= READ_THRESHOLD_TOUCHES)
                .attentionSeconds(progress.getAttentionSeconds())
                .scrollFraction(progress.getScrollFraction())
                .build();
    }
}
