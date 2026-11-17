package com.example.media.consumer.gui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = ConsumerGuiApplication.class)
@AutoConfigureMockMvc
class GuiEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGetVideosReturnsCatalogItems() throws Exception {
        mockMvc.perform(get("/api/videos"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    void testGetStatusReturnsQueueMetrics() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.queue_length").isNumber())
                .andExpect(jsonPath("$.queue_capacity").isNumber())
                .andExpect(jsonPath("$.c").isNumber())
                .andExpect(jsonPath("$.q").isNumber());
    }

    @Test
    void testGetThumbnailReturnsSvgOrImage() throws Exception {
        mockMvc.perform(get("/thumb/mock-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("image")));
    }

    @Test
    void testMediaRangeRequestSeekingSupport() throws Exception {
        mockMvc.perform(get("/media/mock-1")
                        .header(HttpHeaders.RANGE, "bytes=0-100"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("video/mp4")))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().exists(HttpHeaders.CONTENT_RANGE));
    }
}
