package net.java21.blog.backend.topic.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import net.java21.blog.backend.support.WebMvcTestSupport;
import net.java21.blog.backend.topic.dto.TopicNode;
import net.java21.blog.backend.topic.service.TopicService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 공개 주제 트리(T011, contracts/api.md {@code TopicNode}): 비로그인 200, 4개 언어 이름, 카드 색, onTab, 소분류 children []. */
@WebMvcTest(TopicController.class)
@Import(WebMvcTestSupport.class)
class TopicControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private TopicService topicService;

    @Test
    void anonymousGetsTopicTree() throws Exception {
        Map<String, String> names = Map.of("ko", "IT 인터넷", "en", "IT & Internet", "ja", "IT・インターネット",
                "zh-CN", "IT 互联网");
        TopicNode minor = new TopicNode(12L, "it-internet", 5L, names, null, true, List.of());
        when(topicService.publicTree()).thenReturn(List.of(new TopicNode(5L, "knowledge", null,
                Map.of("ko", "지식·동향", "en", "Knowledge", "ja", "知識", "zh-CN", "知识"), "#3D7DD8", true,
                List.of(minor))));

        mvc.perform(get("/api/v1/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result[0].id").value(5))
                .andExpect(jsonPath("$.result[0].slug").value("knowledge"))
                .andExpect(jsonPath("$.result[0].parentId").doesNotExist())
                .andExpect(jsonPath("$.result[0].cardColor").value("#3D7DD8"))
                .andExpect(jsonPath("$.result[0].onTab").value(true))
                .andExpect(jsonPath("$.result[0].children[0].id").value(12))
                .andExpect(jsonPath("$.result[0].children[0].parentId").value(5))
                .andExpect(jsonPath("$.result[0].children[0].names.zh-CN").value("IT 互联网"))
                .andExpect(jsonPath("$.result[0].children[0].names.ja").value("IT・インターネット"))
                .andExpect(jsonPath("$.result[0].children[0].cardColor").doesNotExist())
                .andExpect(jsonPath("$.result[0].children[0].children").isEmpty());
    }
}
