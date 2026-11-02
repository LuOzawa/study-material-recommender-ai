package com.recommend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MaterialDTO {

    @NotBlank
    private String title;

    private String description;
    private Long categoryId;
    private String tags;
    private String author;
}
