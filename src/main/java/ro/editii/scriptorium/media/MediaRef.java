package ro.editii.scriptorium.media;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.BeanUtils;

/**
 * pointer to a remote image, like a photo, a video + some metadata etc.
 */
@Entity
@Data @AllArgsConstructor @Builder @NoArgsConstructor
public class MediaRef {

    @Id
    @Column(name = "url", length = 500)
    @Size(max=500)
    String  url;

    @Size(max=200)
    String  contentType;

    @Min(0)
    Integer width;

    @Min(0)
    Integer height;

    // the role this media ref plays for the associated Textbase resource
    String role;

//    public static MediaRef fromS3Metadata(S3Metadata metadata) {
//        return MediaRef.builder()
//                .contentType(metadata.getContentType())
//                .width(metadata.getWidth())
//                .height(metadata.getHeight())
//                .build();
//    }

    public MediaRef merge(MediaRef other) {
        BeanUtils.copyProperties(other, this);
        return this;
    }
}
