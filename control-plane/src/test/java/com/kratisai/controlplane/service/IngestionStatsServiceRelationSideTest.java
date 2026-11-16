package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.restdto.RelationSide;
import com.kratisai.controlplane.model.Direction;
import org.junit.jupiter.api.Test;

class IngestionStatsServiceRelationSideTest {

    @Test
    void relationSideShouldMapEveryDirectionForBothRepositoryPositions() {
        assertThat(IngestionStatsService.relationSide(Direction.A_TO_B, true)).isEqualTo(RelationSide.UPSTREAM);
        assertThat(IngestionStatsService.relationSide(Direction.A_TO_B, false)).isEqualTo(RelationSide.DOWNSTREAM);
        assertThat(IngestionStatsService.relationSide(Direction.B_TO_A, true)).isEqualTo(RelationSide.DOWNSTREAM);
        assertThat(IngestionStatsService.relationSide(Direction.B_TO_A, false)).isEqualTo(RelationSide.UPSTREAM);
        assertThat(IngestionStatsService.relationSide(Direction.BIDIRECTIONAL, true))
                .isEqualTo(RelationSide.BIDIRECTIONAL);
        assertThat(IngestionStatsService.relationSide(Direction.BIDIRECTIONAL, false))
                .isEqualTo(RelationSide.BIDIRECTIONAL);
        assertThat(IngestionStatsService.relationSide(Direction.UNKNOWN, true)).isEqualTo(RelationSide.UNKNOWN);
        assertThat(IngestionStatsService.relationSide(Direction.UNKNOWN, false)).isEqualTo(RelationSide.UNKNOWN);
    }
}
