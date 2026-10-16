package dev.agentboard.connect.connectors;

import dev.agentboard.connect.Connector;
import dev.agentboard.connect.Observation;
import dev.agentboard.connect.SafeHttp;
import dev.agentboard.connect.With;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recent items from RSS or Atom feeds, such as vendor status pages.
 * Clipped patterns, HTTP details, change key and summary formatting were reconstructed.
 * The visible secure XML parser and date conversion logic are preserved.
 */
@Component
public class RssConnector implements Connector {

    private static final Pattern TROUBLE = Pattern.compile("(?i)outage|degrad|incident|investigat");
    private static final Pattern RESOLVED = Pattern.compile("(?i)resolved|complet|restor");

    private final SafeHttp http;

    public RssConnector(SafeHttp http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "rss";
    }

    @Override
    public Observation fetch(Map<String, Object> with, RunContext context) throws Exception {
        List<String> feeds = With.strings(with, "feeds");
        if (feeds.isEmpty()) throw new IllegalArgumentException("rss needs with: { feeds: [...] }");
        Instant since = Instant.now().minus(Duration.ofHours(With.integer(with, "hours", 24, 24 * 30)));
        List<String> facts = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        String severity = "low";
        for (String feed : feeds) {
            SafeHttp.Response r = http.get(feed, Map.of("Accept", "application/rss+xml, application/atom+xml, application/xml"));
            if (r.status() / 100 != 2) throw new IOException(URI.create(feed).getHost() + " answered HTTP " + r.status());
            String source = URI.create(feed).getHost();
            for (Item item : parse(r.body())) {
                if (item.published() == null || item.published().isBefore(since)) continue;
                String text = item.title() + " " + item.summary();
                boolean open = TROUBLE.matcher(text).find() && !RESOLVED.matcher(text).find();
                if (open) severity = "high";
                facts.add("[" + source + "] " + With.oneLine(item.title(), 160) + " — "
                        + With.oneLine(item.summary(), 250) + (item.link().isBlank()?"":" — "+With.oneLine(item.link(),500)));
                key.append(source).append('|').append(item.title()).append(open ? "!" : ".").append('\n');
            }
        }
        String summary = facts.isEmpty() ? "no new items in " + feeds.size() + " feeds"
                : facts.size() + " recent item" + (facts.size() == 1 ? "" : "s") + " from " + feeds.size() + " feeds";
        return Observation.of(summary, facts, key.toString(), severity);
    }

    /** Secure XML parsing: no DTDs and no external entities. */
    static List<Item> parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(false);
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        List<Item> items = new ArrayList<>();
        NodeList rss = doc.getElementsByTagName("item");
        for (int i = 0; i < rss.getLength() && items.size() < 50; i++) {
            Element e = (Element) rss.item(i);
            items.add(new Item(child(e, "title"), child(e, "description"), date(child(e, "pubDate")), child(e,"link")));
        }
        NodeList atom = doc.getElementsByTagName("entry");
        for (int i = 0; i < atom.getLength() && items.size() < 50; i++) {
            Element e = (Element) atom.item(i);
            String when = child(e, "updated").isEmpty() ? child(e, "published") : child(e, "updated");
            String summary = child(e, "summary").isEmpty() ? child(e, "content") : child(e, "summary");
            items.add(new Item(child(e, "title"), summary, date(when), atomLink(e)));
        }
        return items;
    }

    private static String atomLink(Element e) {
        NodeList links=e.getElementsByTagName("link");
        for(int i=0;i<links.getLength();i++) {Element link=(Element)links.item(i);if(link.getAttribute("rel").isEmpty() || link.getAttribute("rel").equals("alternate"))return link.getAttribute("href");}
        return "";
    }

    private static String child(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().trim();
    }

    private static Instant date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException notRfc) {
            try {
                return OffsetDateTime.parse(s.trim()).toInstant();
            } catch (RuntimeException unknown) {
                return null;
            }
        }
    }

    record Item(String title, String summary, Instant published, String link) {
    }
}
