package com.kratisai.controlplane.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ReadFileToolPagingTest {

    private static List<String> lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "line " + i).toList();
    }

    @Test
    void numbersLinesAndReportsTheRangeOfAShortFile() {
        assertThat(ReadFileTool.page(lines(2), null, null)).isEqualTo("1: line 1\n2: line 2\n[lines 1-2 of 2]");
    }

    @Test
    void returnsAtMostFiveHundredLinesAndNamesTheNextOffset() {
        String page = ReadFileTool.page(lines(1200), null, null);

        assertThat(page).contains("500: line 500\n[lines 1-500 of 1200; continue with offset=501]");
        assertThat(page).doesNotContain("501: line 501");
    }

    @Test
    void offsetAndLimitSelectTheRequestedLines() {
        assertThat(ReadFileTool.page(lines(10), 4, 2))
                .isEqualTo("4: line 4\n5: line 5\n[lines 4-5 of 10; continue with offset=6]");
    }

    @Test
    void limitAboveTheCapIsClampedToFiveHundred() {
        assertThat(ReadFileTool.page(lines(1200), 1, 9000)).contains("[lines 1-500 of 1200; continue with offset=501]");
    }

    @Test
    void offsetPastTheEndIsReportedInsteadOfEmpty() {
        assertThat(ReadFileTool.page(lines(3), 9, null))
                .isEqualTo("[offset 9 is past the end of the file, which has 3 lines]");
    }

    @Test
    void emptyFileIsReported() {
        assertThat(ReadFileTool.page(List.of(), null, null)).isEqualTo("[empty file]");
    }
}
