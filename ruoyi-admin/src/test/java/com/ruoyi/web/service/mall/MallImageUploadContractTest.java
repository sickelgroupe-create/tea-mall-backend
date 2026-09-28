package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.web.controller.mall.MallPartnerContentController;

class MallImageUploadContractTest {
    @TempDir Path temporaryProfile;
    private String previousProfile;
    private MockMvc mvc;
    private MallSessionService sessions;
    private final byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlS8AAAAASUVORK5CYII=");

    @BeforeEach void setUp() {
        previousProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(temporaryProfile.toString().replace('\\', '/'));
        sessions = mock(MallSessionService.class);
        when(sessions.requireMember("member-session")).thenReturn(new MallSessionService.SessionContext(1L, true, "member-session", false));
        when(sessions.requireMember(null)).thenThrow(new IllegalArgumentException("请先登录"));
        MallPartnerContentController controller = new MallPartnerContentController();
        ReflectionTestUtils.setField(controller, "sessions", sessions);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @AfterEach void restoreProfile() { new RuoYiConfig().setProfile(previousProfile); }

    private Map<?,?> upload(MockMultipartFile file, boolean authenticated) throws Exception {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder request = multipart("/mall/community/images").file(file);
        if (authenticated) request.header("X-Mall-Session", "member-session");
        // JSON is UTF-8; MockHttpServletResponse's default text decoder may be ISO-8859-1.
        return new ObjectMapper().readValue(mvc.perform(request).andReturn().getResponse().getContentAsByteArray(), Map.class);
    }

    @Test void multipartUploadReturnsDataPathWhoseStoredBytesMatchTheImage() throws Exception {
        Map<?,?> body = upload(new MockMultipartFile("file", "evidence.png", "image/png", png), true);
        assertEquals(200, body.get("code"));
        assertTrue(body.get("data") instanceof String, "Image path must be in data, not only msg");
        String url = (String)body.get("data");
        assertTrue(url.startsWith("/profile/upload/"));
        assertEquals("上传成功", body.get("msg"));
        Path stored = temporaryProfile.resolve(url.substring("/profile/".length()));
        assertTrue(stored.normalize().startsWith(temporaryProfile));
        assertArrayEquals(png, Files.readAllBytes(stored));
        verify(sessions).requireMember("member-session");
    }

    @Test void repeatedFileNamesGetDistinctPathsWithoutOverwriting() throws Exception {
        Map<?,?> one = upload(new MockMultipartFile("file", "same.png", "image/png", png), true);
        Map<?,?> two = upload(new MockMultipartFile("file", "same.png", "image/png", png), true);
        assertNotNull(one.get("data"));
        assertNotEquals(one.get("data"), two.get("data"));
    }

    @Test void missingFileInvalidImageAndOversizeNeverReturnSuccessfulPaths() throws Exception {
        for (MockMultipartFile file : new MockMultipartFile[] {
            new MockMultipartFile("file", "empty.png", "image/png", new byte[0]),
            new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes("UTF-8")),
            new MockMultipartFile("file", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1])
        }) {
            Map<?,?> body=upload(file,true);
            assertEquals(400,body.get("code"));
            assertNull(body.get("data"));
        }
        assertFalse(Files.exists(temporaryProfile.resolve("upload")));
    }

    @Test void anonymousUploadIsRejectedBeforeSaving() throws Exception {
        Map<?,?> body=upload(new MockMultipartFile("file", "private.png", "image/png", png),false);
        assertEquals(400,body.get("code"));
        assertNull(body.get("data"));
        assertFalse(Files.exists(temporaryProfile.resolve("upload")));
        verify(sessions).requireMember(null);
    }
}
