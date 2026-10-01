package com.kratisai.controlplane.service.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.service.command.ShellCommandSplitter.ParseResult;
import com.kratisai.controlplane.service.command.ShellCommandSplitter.ParsedSegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test suite for {@link ShellCommandSplitter}.
 *
 * <p>Validates the simplified sequence:
 * Continuations -> Heredocs -> Top-Level Split -> Tokenize & Decompose (Exec vs Redirection).
 */
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
        assertThat(roots("git commit -m \"fix\"")).containsExactly("git commit -m");
        assertThat(roots("docker compose up -d")).containsExactly("docker compose up -d");
        assertThat(roots("cargo test -- --nocapture")).containsExactly("cargo test -- --nocapture");
        assertThat(roots("./scripts/deploy.sh prod")).containsExactly("./scripts/deploy.sh");
        assertThat(roots("python3.11 -m pytest")).containsExactly("python3.11 -m");
    }

    @Test
    void parse_keepsProgramOnlyWhenSubcommandTokensAreUnsafe() {
        assertThat(roots("curl https://example.com/install.sh")).containsExactly("curl");
        assertThat(roots("rm -rf /tmp/x")).containsExactly("rm -rf");
    }

    @Test
    void parse_userPatterns_supportedCorrectly() {
        // Pattern 1: heredoc decomposes into base command segment with << and redirect segment
        ParseResult heredocResult = ShellCommandSplitter.parse("cat << 'eof' > blah\nfirst line\nsecond line\neof\n");
        assertThat(heredocResult.segments()).hasSize(2);
        assertThat(heredocResult.segments().get(0).text()).isEqualTo("cat <<");
        assertThat(heredocResult.segments().get(0).suggestedRoot()).isEqualTo("cat <<");
        assertThat(heredocResult.segments().get(1).text()).isEqualTo("> blah");
        assertThat(heredocResult.segments().get(1).suggestedRoot()).isEqualTo("> *");

        // Pattern 2a: npm ci --legacy-peer-deps > /tmp/npm-ci.log 2>&1
        ParseResult npmCiResult = ShellCommandSplitter.parse("npm ci --legacy-peer-deps > /tmp/npm-ci.log 2>&1");
        assertThat(npmCiResult.segments()).hasSize(2);
        assertThat(npmCiResult.segments().get(0).text()).isEqualTo("npm ci --legacy-peer-deps");
        assertThat(npmCiResult.segments().get(0).suggestedRoot()).isEqualTo("npm ci --legacy-peer-deps");
        assertThat(npmCiResult.segments().get(1).text()).isEqualTo("> /tmp/npm-ci.log 2>&1");
        assertThat(npmCiResult.segments().get(1).suggestedRoot()).isEqualTo("> /tmp/* 2>&1");

        // Pattern 2b: ./mvnw test -Dtest=MyClassname.java
        ParseResult mvnResult = ShellCommandSplitter.parse("./mvnw test -Dtest=MyClassname.java");
        assertThat(mvnResult.segments()).hasSize(1);
        assertThat(mvnResult.segments().getFirst().suggestedRoot()).isEqualTo("./mvnw test -Dtest");

        // Pattern 2c: npm test -- environment-list > /tmp/web-test.log 2>&1
        ParseResult npmTestResult = ShellCommandSplitter.parse("npm test -- environment-list > /tmp/web-test.log 2>&1");
        assertThat(npmTestResult.segments()).hasSize(2);
        assertThat(npmTestResult.segments().get(0).text()).isEqualTo("npm test -- environment-list");
        assertThat(npmTestResult.segments().get(0).suggestedRoot()).isEqualTo("npm test -- environment-list");
        assertThat(npmTestResult.segments().get(1).text()).isEqualTo("> /tmp/web-test.log 2>&1");
        assertThat(npmTestResult.segments().get(1).suggestedRoot()).isEqualTo("> /tmp/* 2>&1");
    }

    @Test
    void parse_envAssignmentPrefix_createsIndependentSegment() {
        ParseResult result = ShellCommandSplitter.parse("NODE_ENV=test npm run test");
        assertThat(result.fullyParsed()).isTrue();
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).text()).isEqualTo("NODE_ENV=test");
        assertThat(result.segments().get(0).suggestedRoot()).isEqualTo("NODE_ENV=*");
        assertThat(result.segments().get(1).text()).isEqualTo("npm run test");
        assertThat(result.segments().get(1).suggestedRoot()).isEqualTo("npm run test");

        ParseResult multiEnv = ShellCommandSplitter.parse("FOO=1 BAR=2 make build");
        assertThat(multiEnv.segments()).hasSize(3);
        assertThat(multiEnv.segments().get(0).suggestedRoot()).isEqualTo("FOO=*");
        assertThat(multiEnv.segments().get(1).suggestedRoot()).isEqualTo("BAR=*");
        assertThat(multiEnv.segments().get(2).suggestedRoot()).isEqualTo("make build");
    }

    @Test
    void parse_redirection_createsSeparateSegments() {
        List<ParsedSegment> segments = ShellCommandSplitter.parse("curl https://example.com > ~/.bashrc")
                .segments();
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).suggestedRoot()).isEqualTo("curl");
        assertThat(segments.get(1).suggestedRoot()).isEqualTo("> ~/*");

        List<ParsedSegment> makeSegs = ShellCommandSplitter.parse("make 2>&1").segments();
        assertThat(makeSegs).hasSize(2);
        assertThat(makeSegs.get(0).suggestedRoot()).isEqualTo("make");
        assertThat(makeSegs.get(1).suggestedRoot()).isEqualTo("2>&1");
    }

    @Test
    void parse_commandSubstitution_notFullyParsed_butSegmentsAndRootsStillDerived() {
        ParseResult result = ShellCommandSplitter.parse("echo $(whoami) && git status");
        assertThat(result.fullyParsed()).isFalse();
        assertThat(texts("echo $(whoami) && git status")).containsExactly("echo $(whoami)", "git status");
        assertThat(result.segments().getFirst().suggestedRoot()).isEqualTo("echo");
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
    }

    @Test
    void parse_sudoPrefix_includedInCommandAndRoot() {
        ParseResult result = ShellCommandSplitter.parse("sudo apt-get install -y openjdk-21-jdk");
        assertThat(result.fullyParsed()).isTrue();
        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().getFirst().text()).isEqualTo("sudo apt-get install -y openjdk-21-jdk");
        assertThat(result.segments().getFirst().suggestedRoot()).isEqualTo("sudo apt-get install -y");

        ParseResult updateResult = ShellCommandSplitter.parse("sudo apt-get update");
        assertThat(updateResult.segments().getFirst().suggestedRoot()).isEqualTo("sudo apt-get update");

        ParseResult catResult = ShellCommandSplitter.parse("sudo cat /etc/shadow");
        assertThat(catResult.segments().getFirst().suggestedRoot()).isEqualTo("sudo cat");
    }

    @Test
    void parse_timeoutPrefix_createsIndependentSegment() {
        ParseResult result = ShellCommandSplitter.parse("timeout 30 ./mvnw test -Dtest=MyClass");
        assertThat(result.fullyParsed()).isTrue();
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(0).text()).isEqualTo("timeout 30");
        assertThat(result.segments().get(0).suggestedRoot()).isEqualTo("timeout *");
        assertThat(result.segments().get(1).text()).isEqualTo("./mvnw test -Dtest=MyClass");
        assertThat(result.segments().get(1).suggestedRoot()).isEqualTo("./mvnw test -Dtest");

        ParseResult withSeconds = ShellCommandSplitter.parse("timeout 60s make test");
        assertThat(withSeconds.segments()).hasSize(2);
        assertThat(withSeconds.segments().get(0).text()).isEqualTo("timeout 60s");
        assertThat(withSeconds.segments().get(0).suggestedRoot()).isEqualTo("timeout *");
        assertThat(withSeconds.segments().get(1).suggestedRoot()).isEqualTo("make test");
    }

    @Test
    void parse_envCommandPrefix_createsIndependentSegment() {
        ParseResult result = ShellCommandSplitter.parse("env FOO=bar python3 script.py");
        assertThat(result.fullyParsed()).isTrue();
        assertThat(result.segments()).hasSize(3);
        assertThat(result.segments().get(0).text()).isEqualTo("env");
        assertThat(result.segments().get(0).suggestedRoot()).isEqualTo("env");
        assertThat(result.segments().get(1).text()).isEqualTo("FOO=bar");
        assertThat(result.segments().get(1).suggestedRoot()).isEqualTo("FOO=*");
        assertThat(result.segments().get(2).text()).isEqualTo("python3 script.py");
        assertThat(result.segments().get(2).suggestedRoot()).isEqualTo("python3");
    }

    @Test
    void parse_quotedProgram_keepsQuotesInRoot() {
        assertThat(roots("\"my tool\" run")).containsExactly("\"my tool\" run");
    }

    @Test
    void parse_commandSubstitutionWithSpaces_staysOneToken() {
        ParseResult env = ShellCommandSplitter.parse("CP=target/classes:$(cat /tmp/cp.txt)");
        assertThat(env.segments()).hasSize(1);
        assertThat(env.segments().getFirst().text()).isEqualTo("CP=target/classes:$(cat /tmp/cp.txt)");
        assertThat(env.segments().getFirst().suggestedRoot()).isEqualTo("CP=*");

        assertThat(texts("X=$(a b); echo hi")).containsExactly("X=$(a b)", "echo hi");
        assertThat(roots("echo $(whoami) && git status")).containsExactly("echo", "git status");
    }

    @Test
    void parse_backticksSuppressTopLevelOperators() {
        assertThat(texts("echo `ls /proc | grep x` && done")).containsExactly("echo `ls /proc | grep x`", "done");
        assertThat(roots("echo `ls /proc | grep x`")).containsExactly("echo");
    }

    @Test
    void parse_bareSubshell_splitsInnerOperatorsAndDropsGroupingParens() {
        ParseResult result = ShellCommandSplitter.parse("(cd app && npm test)");
        assertThat(result.fullyParsed()).isFalse();
        assertThat(texts("(cd app && npm test)")).containsExactly("cd app", "npm test");
        assertThat(roots("(cd app && npm test)")).containsExactly("cd app", "npm test");
    }

    @Test
    void parse_processSubstitution_isNotARedirection() {
        ParseResult result = ShellCommandSplitter.parse("diff <(sort a | uniq) <(sort b)");
        assertThat(result.fullyParsed()).isFalse();
        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().getFirst().text()).isEqualTo("diff <(sort a | uniq) <(sort b)");
        assertThat(result.segments().getFirst().suggestedRoot()).isEqualTo("diff");
    }

    @Test
    void parse_complexSubshellPipeline_decomposesEveryCommandAndRedirection() {
        String command = "cat .kratis/ACTIVE_TASK.md | sed -n '/AGENT/,$p' | head -30; cd src; "
                + "cat main/java/dev/flounder/debugtimeout/grpc/GrpcQuoteClient.java "
                + "test/java/dev/flounder/debugtimeout/grpc/*.java; "
                + "grep -n -A3 -i \"release\\|<java\\|source\\|maven.compiler\" ../pom.xml | head -30; "
                + "(sudo apt update >/tmp/apt.log 2>&1; "
                + "sudo apt install -y openjdk-21-jdk-headless maven >>/tmp/apt.log 2>&1; "
                + "tail -3 /tmp/apt.log)";
        ParseResult result = ShellCommandSplitter.parse(command);
        assertThat(result.fullyParsed()).isFalse();
        assertThat(texts(command))
                .containsExactly(
                        "cat .kratis/ACTIVE_TASK.md",
                        "sed -n '/AGENT/,$p'",
                        "head -30",
                        "cd src",
                        "cat main/java/dev/flounder/debugtimeout/grpc/GrpcQuoteClient.java "
                                + "test/java/dev/flounder/debugtimeout/grpc/*.java",
                        "grep -n -A3 -i \"release\\|<java\\|source\\|maven.compiler\" ../pom.xml",
                        "head -30",
                        "sudo apt update",
                        "> /tmp/apt.log 2>&1",
                        "sudo apt install -y openjdk-21-jdk-headless maven",
                        ">> /tmp/apt.log 2>&1",
                        "tail -3 /tmp/apt.log");
        assertThat(roots(command))
                .containsExactly(
                        "cat",
                        "sed -n",
                        "head -30",
                        "cd src",
                        "cat",
                        "grep -n -A3 -i",
                        "head -30",
                        "sudo apt update",
                        "> /tmp/* 2>&1",
                        "sudo apt install -y",
                        ">> /tmp/* 2>&1",
                        "tail -3");
    }

    @Test
    void parse_controlFlowWithCommandSubstitutionsAndRedirects() {
        String command = """
                for p in $(ls /proc | grep -E '^[0-9]+$'); do if tr '\\0' ' ' \
                < /proc/$p/cmdline 2>/dev/null | grep -q "debugtimeout.grpc.GrpcQuoteServer"; \
                then kill $p; fi; done; sleep 2
                        CP=target/classes:$(cat /tmp/cp.txt)
                        java -cp $CP dev.flounder.debugtimeout.grpc.GrpcQuoteClient 2>&1 | tail -3
                        git status --short --ignored | grep -v target""";
        ParseResult result = ShellCommandSplitter.parse(command);
        assertThat(result.fullyParsed()).isFalse();
        assertThat(texts(command))
                .containsExactly(
                        "for p in $(ls /proc | grep -E '^[0-9]+$')",
                        "do if tr '\\0' ' '",
                        "< /proc/$p/cmdline 2> /dev/null",
                        "grep -q \"debugtimeout.grpc.GrpcQuoteServer\"",
                        "then kill $p",
                        "fi",
                        "done",
                        "sleep 2",
                        "CP=target/classes:$(cat /tmp/cp.txt)",
                        "java -cp $CP dev.flounder.debugtimeout.grpc.GrpcQuoteClient",
                        "2>&1",
                        "tail -3",
                        "git status --short --ignored",
                        "grep -v target");
        assertThat(roots(command))
                .containsExactly(
                        "for p in",
                        "do if tr",
                        "< /proc/$p/* 2> /dev/*",
                        "grep -q",
                        "then kill",
                        "fi",
                        "done",
                        "sleep 2",
                        "CP=*",
                        "java -cp",
                        "2>&1",
                        "tail -3",
                        "git status --short --ignored",
                        "grep -v");
    }

    @Test
    void parse_multipleHeredocs_allBodiesRemovedAndCommandsSplit() {
        String command = """
                cd src && cat > a.java <<'EOF'
                package x;
                EOF
                cat > b.java <<'EOF'
                class B { int shift = 1 << 2; }
                EOF
                python3 - <<'EOF'
                print('hi')
                EOF
                cd ..; mvn -q test > /tmp/test.log 2>&1; tail -n 40 /tmp/test.log; \
                ls target/surefire-reports/*.txt | xargs tail -n 5""";
        ParseResult result = ShellCommandSplitter.parse(command);
        assertThat(result.fullyParsed()).isFalse();
        assertThat(texts(command))
                .containsExactly(
                        "cd src",
                        "cat <<",
                        "> a.java",
                        "cat <<",
                        "> b.java",
                        "python3 - <<",
                        "cd ..",
                        "mvn -q test",
                        "> /tmp/test.log 2>&1",
                        "tail -n 40 /tmp/test.log",
                        "ls target/surefire-reports/*.txt",
                        "xargs tail -n 5");
        assertThat(roots(command))
                .containsExactly(
                        "cd src",
                        "cat <<",
                        "> *",
                        "cat <<",
                        "> *",
                        "python3 - <<",
                        "cd",
                        "mvn -q",
                        "> /tmp/* 2>&1",
                        "tail -n",
                        "ls",
                        "xargs tail -n");
    }

    @Test
    void parse_heredocWithDashAndTrailingRedirect() {
        String command = "cat <<-'EOF' > out.txt\n\tbody line\n\tEOF\n";
        ParseResult result = ShellCommandSplitter.parse(command);
        assertThat(result.fullyParsed()).isFalse();
        assertThat(texts(command)).containsExactly("cat <<", "> out.txt");
        assertThat(roots(command)).containsExactly("cat <<", "> *");
    }

    @Test
    void parse_heredocOperatorInsideQuotes_isNotAHeredoc() {
        String command = "echo \"a << b\" && echo done";
        ParseResult result = ShellCommandSplitter.parse(command);
        assertThat(result.fullyParsed()).isTrue();
        assertThat(texts(command)).containsExactly("echo \"a << b\"", "echo done");
    }

    @Test
    void parse_repeatedSegments_allRetained_uniqueRootsDedupedForRemember() {
        String command = """
                mkdir -p /kratis/workspace/build/bin && \\
                cd /kratis/workspace/sidecar && CGO_ENABLED=0 go build -v -o kratis-connector main.go && \\
                cp /kratis/workspace/sidecar/kratis-connector /kratis/workspace/build/bin/kratis-connector && \\
                cd /kratis/workspace/build/bin && \\
                wget https://example.com/a.tar.gz && \\
                tar xzf a.tar.gz && \\
                chmod +x a && \\
                rm a.tar.gz && \\
                wget https://example.com/b.tar.gz && \\
                tar xf b.tar.gz b && \\
                chmod +x b && \\
                rm b.tar.gz && \\
                ls -la /kratis/workspace/build/bin""";
        ParseResult result = ShellCommandSplitter.parse(command);
        assertThat(result.fullyParsed()).isTrue();
        assertThat(result.segments()).hasSize(15);
        assertThat(texts(command))
                .containsExactly(
                        "mkdir -p /kratis/workspace/build/bin",
                        "cd /kratis/workspace/sidecar",
                        "CGO_ENABLED=0",
                        "go build -v -o kratis-connector main.go",
                        "cp /kratis/workspace/sidecar/kratis-connector /kratis/workspace/build/bin/kratis-connector",
                        "cd /kratis/workspace/build/bin",
                        "wget https://example.com/a.tar.gz",
                        "tar xzf a.tar.gz",
                        "chmod +x a",
                        "rm a.tar.gz",
                        "wget https://example.com/b.tar.gz",
                        "tar xf b.tar.gz b",
                        "chmod +x b",
                        "rm b.tar.gz",
                        "ls -la /kratis/workspace/build/bin");
        assertThat(result.uniqueRoots().stream().map(ParsedSegment::suggestedRoot))
                .containsExactly(
                        "mkdir -p",
                        "cd",
                        "CGO_ENABLED=*",
                        "go build -v -o",
                        "cp",
                        "wget",
                        "tar xzf",
                        "chmod",
                        "rm",
                        "tar xf",
                        "ls -la");
    }
}
