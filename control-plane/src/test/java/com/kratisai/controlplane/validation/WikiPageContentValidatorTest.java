package com.kratisai.controlplane.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.agentloop.KratisToolException;
import org.junit.jupiter.api.Test;

class WikiPageContentValidatorTest {

    private final WikiPageContentValidator validator = new WikiPageContentValidator();

    @Test
    void rejectsContentBelowProseMinimum() {
        assertThatThrownBy(() -> validator.requireBody("# Authentication & Security\n\nbody\n"))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("at least 80 words of prose")
                .hasMessageContaining("this page has 1")
                .hasMessageContaining("Add a substantive body and retry");
    }

    @Test
    void rejectsHeadingOnlyContent() {
        assertThatThrownBy(() -> validator.requireBody("# Authentication & Security\n"))
                .isInstanceOf(KratisToolException.class)
                .hasMessageContaining("this page has 0");
    }

    @Test
    void countsParagraphsAndListItemsAsProse() {
        assertThatCode(() -> validator.requireBody("# Authentication\n\n" + words(40) + "\n\n- " + words(40) + "\n"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsExactlyTheProseMinimum() {
        assertThatCode(() -> validator.requireBody(words(WikiPageContentValidator.MIN_PROSE_WORDS)))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotCountHeadingOrCodeWordsTowardsTheMinimum() {
        String content = "# " + words(100) + "\n\n```text\n" + words(100) + "\n```\n\n" + words(79) + "\n";
        assertThat(validator.countProseWords(content)).isEqualTo(79);
    }

    @Test
    void countsVisibleLinkTextButNotImageAltTextInlineCodeOrRawHtml() {
        assertThat(validator.countProseWords("[visible link text](https://example.com)"))
                .isEqualTo(3);
        assertThat(validator.countProseWords("![many words in alt text](image.png)"))
                .isZero();
        assertThat(validator.countProseWords("`inline code words`")).isZero();
        assertThat(validator.countProseWords("<div>\nraw html words\n</div>\n")).isZero();
    }

    private static String words(int count) {
        return ("word ").repeat(count).trim();
    }
}
