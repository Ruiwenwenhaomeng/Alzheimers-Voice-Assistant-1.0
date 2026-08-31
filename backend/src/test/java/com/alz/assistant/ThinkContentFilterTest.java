package com.alz.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThinkContentFilterTest {

    @Test
    void removesCompleteAndLeadingReasoningBlocks() {
        assertEquals("最终回答", ThinkContentFilter.strip("<think>内部推理</think>最终回答"));
        assertEquals("最终回答", ThinkContentFilter.strip("没有开始标签的推理</think>最终回答"));
    }

    @Test
    void filtersThinkTagsSplitAcrossStreamChunks() {
        StreamingThinkFilter filter = new StreamingThinkFilter();

        assertEquals("答", filter.accept("答<th"));
        assertEquals("", filter.accept("ink>不可见推"));
        assertEquals("", filter.accept("理</thi"));
        assertEquals("案", filter.accept("nk>案"));
        assertEquals("", filter.finish());
    }

    @Test
    void preservesNormalStreamingTextAndWhitespace() {
        StreamingThinkFilter filter = new StreamingThinkFilter();

        assertEquals("第一段 ", filter.accept("第一段 "));
        assertEquals("second\n", filter.accept("second\n"));
        assertEquals("", filter.finish());
    }
}
