package com.ruoyi.web.service.mall;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MallHtmlSanitizerTest
{
    @Test
    void keepsArticleMarkupAndRemovesExecutableContent()
    {
        String cleaned = MallHtmlSanitizer.sanitizeArticle(
                "<p class='lead'>茶叶</p><script>alert(1)</script>"
              + "<img src='/profile/upload/tea.jpg' onerror='alert(2)'>"
              + "<a href='javascript:alert(3)'>危险</a>");
        assertTrue(cleaned.contains("<p class=\"lead\">茶叶</p>"));
        assertTrue(cleaned.contains("/profile/upload/tea.jpg"));
        assertFalse(cleaned.toLowerCase().contains("script"));
        assertFalse(cleaned.toLowerCase().contains("onerror"));
        assertFalse(cleaned.toLowerCase().contains("javascript:"));
    }
}
