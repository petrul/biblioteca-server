package ro.editii.scriptorium.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class UserLoggedInDto {
    Long userId;
    String username;
    String provider;
    Instant loginAt;
}
