package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.ScratchpadEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class ScratchpadServiceTest {

    @Autowired
    private ScratchpadService scratchpadService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    private static final UUID TEST_CHAT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
    }

    @Test
    void shouldAddFact() {
        ScratchpadEntity result = scratchpadService.addFact(TEST_CHAT, "Java is a programming language");

        assertThat(result).isNotNull();
        assertThat(result.getChatId()).isEqualTo(TEST_CHAT);
        assertThat(result.getFact()).isEqualTo("Java is a programming language");
    }

    @Test
    void shouldGetFactsForSession() {
        scratchpadService.addFact(TEST_CHAT, "Fact 1");
        scratchpadService.addFact(TEST_CHAT, "Fact 2");
        scratchpadService.addFact(TEST_CHAT, "Fact 3");

        List<ScratchpadEntity> facts = scratchpadService.getFacts(TEST_CHAT);

        assertThat(facts).hasSize(3);
        assertThat(facts).extracting(ScratchpadEntity::getFact).containsExactlyInAnyOrder("Fact 1", "Fact 2", "Fact 3");
    }

    @Test
    void shouldReturnEmptyListForUnknownSession() {
        List<ScratchpadEntity> facts = scratchpadService.getFacts(UUID.randomUUID());

        assertThat(facts).isEmpty();
    }

    @Test
    void shouldDeleteSpecificFact() {
        scratchpadService.addFact(TEST_CHAT, "Keep this");
        scratchpadService.addFact(TEST_CHAT, "Delete this");

        scratchpadService.deleteFact(TEST_CHAT, "Delete this");

        List<ScratchpadEntity> facts = scratchpadService.getFacts(TEST_CHAT);
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).getFact()).isEqualTo("Keep this");
    }

    @Test
    void shouldDeleteAllFactsForSession() {
        scratchpadService.addFact(TEST_CHAT, "Fact 1");
        scratchpadService.addFact(TEST_CHAT, "Fact 2");

        scratchpadService.deleteAllFacts(TEST_CHAT);

        List<ScratchpadEntity> facts = scratchpadService.getFacts(TEST_CHAT);
        assertThat(facts).isEmpty();
    }

    @Test
    void shouldGetFactsAsString() {
        scratchpadService.addFact(TEST_CHAT, "First fact");
        scratchpadService.addFact(TEST_CHAT, "Second fact");

        String result = scratchpadService.getFactsAsString(TEST_CHAT);

        assertThat(result).contains("- First fact");
        assertThat(result).contains("- Second fact");
    }

    @Test
    void shouldReturnEmptyStringForNoFacts() {
        String result = scratchpadService.getFactsAsString(TEST_CHAT);

        assertThat(result).isBlank();
    }

    @Test
    void shouldIsolateFactsBySession() {
        var session_a = UUID.randomUUID();
        var session_b = UUID.randomUUID();
        scratchpadService.addFact(session_a, "A fact");
        scratchpadService.addFact(session_b, "B fact");

        List<ScratchpadEntity> factsA = scratchpadService.getFacts(session_a);
        List<ScratchpadEntity> factsB = scratchpadService.getFacts(session_b);

        assertThat(factsA).hasSize(1);
        assertThat(factsA.getFirst().getFact()).isEqualTo("A fact");
        assertThat(factsB).hasSize(1);
        assertThat(factsB.getFirst().getFact()).isEqualTo("B fact");
    }
}
