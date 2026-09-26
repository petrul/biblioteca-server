package ro.editii.scriptorium.search;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@EqualsAndHashCode(callSuper = true)
public class VectorHit extends Hit {

    public VectorHit(String url, Float score, String content) {
        super(url, score);
        this.content = content;
    }

    @Getter
    String content;

    public String toString() {
        return String.format("VectorHit[%s - %f - %s]", this.getUrl(), this.getScore(), this.getContent());
    }

    public static VectorHit from(String url, float score, String content) {
        return new VectorHit(url, score, content);
    }
}
