package com.kratisai.controlplane.validation;

import com.kratisai.controlplane.agentloop.KratisToolException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

@Component
public class WikiPageContentValidator {

    static final int MIN_PROSE_WORDS = 80;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");
    private final Parser parser = Parser.builder().build();

    public void requireBody(String content) {
        int wordCount = countProseWords(content);
        if (wordCount < MIN_PROSE_WORDS) {
            throw new KratisToolException("content must contain at least " + MIN_PROSE_WORDS
                    + " words of prose outside headings, code, and raw HTML; this page has " + wordCount
                    + ". Add a substantive body and retry.");
        }
    }

    int countProseWords(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        int[] count = {0};
        parser.parse(content).accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                // Headings are structural; only count the page body.
            }

            @Override
            public void visit(Image image) {
                // Alt text is not rendered prose.
            }

            @Override
            public void visit(Text text) {
                Matcher matcher = WORD.matcher(text.getLiteral());
                while (matcher.find()) {
                    count[0]++;
                }
            }
        });
        return count[0];
    }
}
