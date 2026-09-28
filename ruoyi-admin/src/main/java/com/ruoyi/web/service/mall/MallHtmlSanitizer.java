package com.ruoyi.web.service.mall;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/** Central whitelist sanitizer for managed rich-text content. */
public final class MallHtmlSanitizer
{
    private static final Safelist ARTICLE_SAFE_LIST = Safelist.relaxed()
            .addTags("figure", "figcaption")
            .addAttributes(":all", "class")
            .addAttributes("a", "target", "rel")
            .addAttributes("img", "alt", "width", "height")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            .preserveRelativeLinks(true);

    private static final Document.OutputSettings OUTPUT_SETTINGS = new Document.OutputSettings()
            .prettyPrint(false);

    private MallHtmlSanitizer()
    {
    }

    public static String sanitizeArticle(String html)
    {
        return Jsoup.clean(html == null ? "" : html, "https://chaye.okam.top", ARTICLE_SAFE_LIST, OUTPUT_SETTINGS);
    }
}
