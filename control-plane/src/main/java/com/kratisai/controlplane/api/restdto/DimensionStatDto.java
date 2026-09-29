package com.kratisai.controlplane.api.restdto;

import com.kratisai.controlplane.model.DimensionCategory;
import java.util.List;

public record DimensionStatDto(
        DimensionCategory category, String name, int fileCount, String synopsis, List<String> topFiles) {}
