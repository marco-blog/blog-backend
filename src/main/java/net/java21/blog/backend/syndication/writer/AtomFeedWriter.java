package net.java21.blog.backend.syndication.writer;

import java.util.ArrayList;
import java.util.List;

import com.rometools.rome.feed.atom.Category;
import com.rometools.rome.feed.atom.Content;
import com.rometools.rome.feed.atom.Entry;
import com.rometools.rome.feed.atom.Feed;
import com.rometools.rome.feed.atom.Link;
import com.rometools.rome.feed.atom.Person;
import com.rometools.rome.feed.synd.SyndPerson;

import net.java21.blog.backend.syndication.service.FeedEntry;
import net.java21.blog.backend.syndication.service.FeedSnapshot;
import org.springframework.stereotype.Component;

/**
 * Atom 1.0 작성(RFC 4287, ROME, 002 research D6). 피드: {@code id}(피드 주소)·{@code title}·{@code subtitle}·{@code updated}(RFC 3339)·
 * {@code author/name}·{@code link rel="alternate"}(블로그 홈)·{@code link rel="self"}. 항목: {@code id}(글 주소)·{@code title}·
 * {@code link rel="alternate"}·{@code published}·{@code updated}·{@code content type="html"}(FULL) 또는 {@code summary}(SUMMARY)·
 * {@code category term}.
 */
@Component
public class AtomFeedWriter {

    public static final String CONTENT_TYPE = "application/atom+xml;charset=UTF-8";

    public byte[] write(FeedSnapshot feed) {
        Feed atom = new Feed("atom_1.0");
        atom.setId(feed.atomUrl());
        atom.setTitleEx(text(feed.title()));
        atom.setSubtitle(text(feed.description()));
        atom.setUpdated(FeedXml.date(feed.updated()));
        atom.setAuthors(authors(feed.author()));
        atom.setAlternateLinks(new ArrayList<>(List.of(link("alternate", feed.link(), "text/html"))));
        atom.setOtherLinks(new ArrayList<>(List.of(link("self", feed.atomUrl(), "application/atom+xml"))));
        atom.setEntries(feed.entries().stream().map(AtomFeedWriter::entry).toList());
        return FeedXml.output(atom);
    }

    private static Entry entry(FeedEntry source) {
        Entry entry = new Entry();
        entry.setId(source.link());
        entry.setTitleEx(text(source.title()));
        entry.setAlternateLinks(new ArrayList<>(List.of(link("alternate", source.link(), "text/html"))));
        entry.setPublished(FeedXml.date(source.published()));
        entry.setUpdated(FeedXml.date(source.updated() != null ? source.updated() : source.published()));
        if (source.contentHtml() != null) {
            Content content = new Content();
            content.setType(Content.HTML);
            content.setValue(FeedXml.clean(source.contentHtml()));
            entry.setContents(new ArrayList<>(List.of(content)));
        } else if (source.summary() != null && !source.summary().isBlank()) {
            entry.setSummary(text(source.summary()));
        }
        entry.setCategories(source.categories().stream().map(name -> {
            Category category = new Category();
            category.setTerm(FeedXml.clean(name));
            return category;
        }).toList());
        return entry;
    }

    private static Content text(String value) {
        Content content = new Content();
        content.setType(Content.TEXT);
        content.setValue(FeedXml.clean(value));
        return content;
    }

    private static Link link(String rel, String href, String type) {
        Link link = new Link();
        link.setRel(rel);
        link.setHref(href);
        link.setType(type);
        return link;
    }

    private static List<SyndPerson> authors(String name) {
        Person person = new Person();
        person.setName(FeedXml.clean(name));
        return new ArrayList<>(List.of(person));
    }
}
