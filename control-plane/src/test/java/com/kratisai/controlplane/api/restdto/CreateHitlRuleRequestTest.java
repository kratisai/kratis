package com.kratisai.controlplane.api.restdto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.kratisai.controlplane.model.HitlRuleAction;
import com.kratisai.controlplane.model.HitlRuleType;
import org.junit.jupiter.api.Test;

class CreateHitlRuleRequestTest {

    @Test
    void defaultsToPrefixWildAllow() {
        CreateHitlRuleRequest request = new CreateHitlRuleRequest("npm run test", null, null);

        assertThat(request.ruleType()).isEqualTo(HitlRuleType.PREFIX_WILD);
        assertThat(request.action()).isEqualTo(HitlRuleAction.ALLOW);
        assertThat(request.commandRoot()).isEqualTo("npm run test");
    }

    @Test
    void normalizesWhitespace() {
        assertThat(new CreateHitlRuleRequest("  npm   run\ttest ", null, null).commandRoot())
                .isEqualTo("npm run test");
    }

    @Test
    void toolKindRootIsNormalizedToWireValue() {
        assertThat(new CreateHitlRuleRequest(" Edit ", HitlRuleType.TOOL_KIND, null).commandRoot())
                .isEqualTo("edit");
        assertThat(new CreateHitlRuleRequest("switch_mode", HitlRuleType.TOOL_KIND, null).commandRoot())
                .isEqualTo("switch_mode");
        assertThat(new CreateHitlRuleRequest("write", HitlRuleType.TOOL_KIND, null).commandRoot())
                .isEqualTo("write");
    }

    @Test
    void toolKindRootOutsideClosedVocabularyIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateHitlRuleRequest("fs/write_text_file", HitlRuleType.TOOL_KIND, null))
                .withMessageContaining("Unknown tool kind");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateHitlRuleRequest("deploy", HitlRuleType.TOOL_KIND, null));
    }

    @Test
    void blankAndOverlongRootsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CreateHitlRuleRequest("   ", null, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateHitlRuleRequest("a".repeat(501), null, null))
                .withMessageContaining("too long");
    }
}
