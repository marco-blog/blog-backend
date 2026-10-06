package net.java21.blog.backend.syndication.writer;

import java.util.ArrayList;
import java.util.List;

import com.rometools.rome.feed.module.DCModule;
import com.rometools.rome.feed.module.DCModuleImpl;
import com.rometools.rome.feed.module.Module;
import com.rometools.rome.feed.rss.Category;
import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.feed.rss.Description;
import com.rometools.rome.feed.rss.Guid;
import com.rometools.rome.feed.rss.Item;

import org.jdom2.Element;
import org.jdom2.Namespace;

import net.java21.blog.backend.syndication.service.FeedEntry;
import net.java21.blog.backend.syndication.service.FeedSnapshot;
import org.springframework.stereotype.Component;

/**
 * RSS 2.0 작성(ROME, 002 research D6, contracts/api.md 블로그 피드 절). 채널: {@code title}·{@code link}·{@code description}·
 * {@code atom:link rel="self"}·{@code lastBuildDate}. 항목: {@code title}·{@code link}·{@code guid}(글 주소, isPermaLink 기본값 true)·
 * {@code pubDate}(RFC 822)·{@code description}(FULL 본문 HTML 또는 SUMMARY 요약)·{@code category}·{@code dc:creator}(이메일을 쓰는
 * {@code author} 대신).
 */
@Component
public class RssFeedWriter {

    public static final String CONTENT_TYPE = "application/rss+xml;charset=UTF-8";
    private static final Namespace ATOM = Namespace.getNamespace("atom", "http://www.w3.org/2005/Atom");

    public byte[] write(FeedSnapshot feed) {
        Channel channel = new Channel("rss_2.0");
        channel.setTitle(FeedXml.clean(feed.title()));
        channel.setLink(feed.link());
        channel.setDescription(FeedXml.clean(feed.description()));
        channel.setLastBuildDate(FeedXml.date(feed.updated()));
        Element self = new Element("link", ATOM);
        self.setAttribute("href", feed.rssUrl());
        self.setAttribute("rel", "self");
        self.setAttribute("type", "application/rss+xml");
        List<Element> foreign = new ArrayList<>();
        foreign.add(self);
        channel.setForeignMarkup(foreign);
        channel.setItems(feed.entries().stream().map(entry -> item(entry, feed.author())).toList());
        return FeedXml.output(channel);
    }

    private static Item item(FeedEntry entry, String author) {
        Item item = new Item();
        item.setTitle(FeedXml.clean(entry.title()));
        item.setLink(entry.link());
        Guid guid = new Guid();
        guid.setValue(entry.link());
        guid.setPermaLink(true);
        item.setGuid(guid);
        item.setPubDate(FeedXml.date(entry.published()));
        String body = entry.contentHtml() != null ? entry.contentHtml() : entry.summary();
        if (body != null && !body.isBlank()) {
            Description description = new Description();
            description.setType(entry.contentHtml() != null ? "text/html" : "text/plain");
            description.setValue(FeedXml.clean(body));
            item.setDescription(description);
        }
        item.setCategories(entry.categories().stream().map(name -> {
            Category category = new Category();
            category.setValue(FeedXml.clean(name));
            return category;
        }).toList());
        DCModule dc = new DCModuleImpl();
        dc.setCreator(FeedXml.clean(author));
        List<Module> modules = new ArrayList<>();
        modules.add(dc);
        item.setModules(modules);
        return item;
    }
}
