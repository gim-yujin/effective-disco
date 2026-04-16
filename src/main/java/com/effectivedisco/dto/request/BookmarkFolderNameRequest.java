package com.effectivedisco.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "북마크 폴더 이름 요청")
public class BookmarkFolderNameRequest {

    @Schema(description = "폴더 이름", example = "나중에 읽기")
    @NotBlank(message = "폴더 이름을 입력하세요.")
    @Size(max = 50, message = "폴더 이름은 50자 이내로 입력하세요.")
    private String name;
}
