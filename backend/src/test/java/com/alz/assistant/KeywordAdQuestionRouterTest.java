package com.alz.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordAdQuestionRouterTest {

    private final AdQuestionRouter router = new KeywordAdQuestionRouter();

    @Test
    void routesIntroductionQuestion() {
        AdQuestionRoute route = router.route("阿尔茨海默病是什么原因引起的？");

        assertEquals(AdQuestionCategory.INTRODUCTION, route.category());
        assertFalse(route.urgent());
    }

    @Test
    void routesSymptomQuestion() {
        AdQuestionRoute route = router.route("老人最近经常迷路，还反复忘事");

        assertEquals(AdQuestionCategory.SYMPTOMS, route.category());
    }

    @Test
    void routesRiskReductionQuestionToCoping() {
        AdQuestionRoute route = router.route("如何降低阿尔茨海默病风险？");

        assertEquals(AdQuestionCategory.COPING, route.category());
    }

    @Test
    void prioritizesEmergencySignals() {
        AdQuestionRoute route = router.route("老人突然说不清话而且一侧无力，是阿尔茨海默病吗？");

        assertEquals(AdQuestionCategory.EMERGENCY, route.category());
        assertTrue(route.urgent());
    }

    @Test
    void rejectsUnrelatedQuestionFromKnowledgeDomains() {
        AdQuestionRoute route = router.route("明天上海天气怎么样？");

        assertEquals(AdQuestionCategory.OUT_OF_SCOPE, route.category());
    }
}
