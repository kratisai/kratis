package com.kratisai.controlplane.service.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.service.command.ShellCommandSplitter.ParseResult;
import com.kratisai.controlplane.service.command.ShellCommandSplitter.ParsedSegment;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShellCommandSplitterTest {

    private static List<String> roots(String command) {
        return ShellCommandSplitter.parse(command).segments().stream()
                .map(ParsedSegment::suggestedRoot)
                .toList();
    }

    private static List<String> texts(String command) {
        return ShellCommandSplitter.parse(command).segments().stream()
                .map(ParsedSegment::text)
                .toList();
    }

    @Test
    void parse_singleCommand_oneSegment() {
        ParseResult result = ShellCommandSplitter.parse("git status");
        assertThat(result.fullyParsed()).isTrue();
        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().getFirst().text()).isEqualTo("git status");
        assertThat(result.segments().getFirst().suggestedRoot()).isEqualTo("git status");
        assertThat(result.segments().getFirst().autoAllowable()).isTrue();
    }

    @Test
    void parse_splitsOnAllTopLevelOperators() {
        assertThat(texts("npm test && npm run lint")).containsExactly("npm test", "npm run lint");
        assertThat(texts("a || b")).containsExactly("a", "b");
        assertThat(texts("cat file.txt | grep foo | wc -l")).containsExactly("cat file.txt", "grep foo", "wc -l");
        assertThat(texts("git add . ; git commit -m x")).containsExactly("git add .", "git commit -m x");
        assertThat(texts("sleep 10 &")).containsExactly("sleep 10");
        assertThat(texts("echo one\necho two")).containsExactly("echo one", "echo two");
        assertThat(texts("make build |& tee log.txt")).containsExactly("make build", "tee log.txt");
    }

    @Test
    void parse_operatorsInsideQuotesDoNotSplit() {
        assertThat(texts("git commit -m \"a && b || c; d\"")).hasSize(1);
        assertThat(texts("git commit -m 'x | y'")).hasSize(1);
        assertThat(texts("echo \"escaped \\\" quote && more\" && echo done"))
                .containsExactly("echo \"escaped \\\" quote && more\"", "echo done");
    }

    @Test
    void parse_derivesRoots_stopsBeforeFlagsPathsAndFiles() {
        assertThat(roots("npm run test src/foo.test.ts")).containsExactly("npm run test");
        assertThat(roots("git commit -m \"fix\"")).containsExactly("git commit");
        assertThat(roots("docker compose up -d")).containsExactly("docker compose up");
        assertThat(roots("cargo test -- --nocapture")).containsExactly("cargo test");
        assertThat(roots("./scripts/deploy.sh prod")).containsExactly("./scripts/deploy.sh");
        assertThat(roots("python3.11 -m pytest")).containsExactly("python3.11");
    }

    @Test
    void parse_keepsProgramOnlyWhenSubcommandTokensAreUnsafe() {
        assertThat(roots("curl https://example.com/install.sh")).containsExactly("curl");
        assertThat(roots("rm -rf /tmp/x")).containsExactly("rm");
    }

    @Test
    void parse_envAssignmentPrefix_notAutoAllowable() {
        ParseResult result = ShellCommandSplitter.parse("NODE_ENV=test npm run test");
        assertThat(result.fullyParsed()).isTrue();
        ParsedSegment segment = result.segments().getFirst();
        assertThat(segment.suggestedRoot()).isEqualTo("npm run test");
        assertThat(segment.autoAllowable()).isFalse();
    }

    @Test
    void parse_redirection_notAutoAllowable() {
        ParsedSegment segment = ShellCommandSplitter.parse("curl https://example.com > ~/.bashrc")
                .segments()
                .getFirst();
        assertThat(segment.suggestedRoot()).isEqualTo("curl");
        assertThat(segment.autoAllowable()).isFalse();

        assertThat(ShellCommandSplitter.parse("make 2>&1").segments().getFirst().autoAllowable())
                .isFalse();
    }

    @Test
    void parse_expansions_neverAutoAllowable() {
        assertThat(ShellCommandSplitter.parse("echo $HOME")
                        .segments()
                        .getFirst()
                        .autoAllowable())
                .isFalse();
        assertThat(ShellCommandSplitter.parse("$CMD install")
                        .segments()
                        .getFirst()
                        .autoAllowable())
                .isFalse();
    }

    @Test
    void parse_commandSubstitution_notFullyParsed_butSegmentsAndRootsStillDerived() {
        ParseResult result = ShellCommandSplitter.parse("echo $(whoami) && git status");
        assertThat(result.fullyParsed()).isFalse();
        assertThat(texts("echo $(whoami) && git status")).containsExactly("echo $(whoami)", "git status");
        assertThat(result.segments().getFirst().suggestedRoot()).isEqualTo("echo");
        assertThat(result.segments().stream().noneMatch(ParsedSegment::autoAllowable))
                .isTrue();
    }

    @Test
    void parse_backticksProcessSubstitutionHeredocSubshell_notFullyParsed() {
        assertThat(ShellCommandSplitter.parse("echo `whoami`").fullyParsed()).isFalse();
        assertThat(ShellCommandSplitter.parse("diff <(sort a) <(sort b)").fullyParsed())
                .isFalse();
        assertThat(ShellCommandSplitter.parse("cat <<EOF").fullyParsed()).isFalse();
        assertThat(ShellCommandSplitter.parse("(cd app && npm test)").fullyParsed())
                .isFalse();
    }

    @Test
    void parse_unbalancedQuotes_notFullyParsed() {
        assertThat(ShellCommandSplitter.parse("git commit -m \"unterminated").fullyParsed())
                .isFalse();
        assertThat(ShellCommandSplitter.parse("echo 'dangling").fullyParsed()).isFalse();
    }

    @Test
    void parse_escapedOperatorsDoNotSplit() {
        assertThat(texts("echo a \\&\\& b")).hasSize(1);
        assertThat(texts("echo x \\; y")).hasSize(1);
    }

    @Test
    void parse_blankAndNull() {
        assertThat(ShellCommandSplitter.parse(null).segments()).isEmpty();
        assertThat(ShellCommandSplitter.parse(null).fullyParsed()).isTrue();
        assertThat(ShellCommandSplitter.parse("   ").segments()).isEmpty();
    }

    @Test
    void parse_emptySegmentsBetweenOperatorsAreDropped() {
        assertThat(texts("a && && b")).containsExactly("a", "b");
        assertThat(texts(";;")).isEmpty();
    }

    @Test
    void parse_crlfNewlinesSplit() {
        assertThat(texts("echo one\r\necho two")).containsExactly("echo one", "echo two");
    }

    @Test
    void parse_redirectOnlySegment_noRoot() {
        ParsedSegment segment =
                ShellCommandSplitter.parse("> out.txt").segments().getFirst();
        assertThat(segment.suggestedRoot()).isEmpty();
        assertThat(segment.autoAllowable()).isFalse();
    }

    @Test
    void parse_quotedProgram_keepsQuotesInRoot() {
        assertThat(roots("\"my tool\" run")).containsExactly("\"my tool\" run");
    }
}
