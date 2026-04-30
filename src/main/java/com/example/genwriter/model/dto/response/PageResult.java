package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PageResult<T> {

    private List<T> items;
    private long total;
    private int page;
    private int size;
}
