package dev.agentboard.connect.connectors;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RssConnectorTest {
    @Test void rssRetainsTheSourceLinkAndPublicationDate()throws Exception{
        var items=RssConnector.parse("<rss><channel><item><title>Release</title><description>Details</description><pubDate>Fri, 16 Oct 2026 10:00:00 GMT</pubDate><link>https://example.org/release</link></item></channel></rss>");
        assertThat(items).hasSize(1);assertThat(items.get(0).link()).isEqualTo("https://example.org/release");assertThat(items.get(0).published()).isNotNull();
    }
    @Test void atomChoosesAlternateLinkOverSelf()throws Exception{
        var items=RssConnector.parse("<feed xmlns='http://www.w3.org/2005/Atom'><entry><title>Update</title><updated>2026-10-16T10:00:00Z</updated><link rel='self' href='https://example.org/feed'/><link rel='alternate' href='https://example.org/article'/></entry></feed>");
        assertThat(items.get(0).link()).isEqualTo("https://example.org/article");assertThat(items.get(0).published()).isNotNull();
    }
    @Test void externalEntityDeclarationsAreRejected(){
        assertThatThrownBy(()->RssConnector.parse("<!DOCTYPE rss [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><rss><channel><item><title>&secret;</title></item></channel></rss>")).isInstanceOf(org.xml.sax.SAXParseException.class);
    }
}
