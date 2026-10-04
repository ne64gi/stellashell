package net.fuyumori.stellashell.core.search;

import java.net.URI;
import java.net.URLDecoder;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SearchEngineTest {
    @Test public void unicodeAndReservedCharactersRemainOneSearchValue() throws Exception {
        String query="日本語 🚀 &next=wrong + # %";
        for(SearchEngine engine:new SearchEngine[]{SearchEngine.GOOGLE,SearchEngine.DUCKDUCKGO,SearchEngine.BING}){
            URI uri=new URI(engine.url(query));assertNull(uri.getFragment());
            assertFalse(uri.getRawQuery().contains("&"));assertFalse(uri.getRawQuery().contains("+"));
            assertEquals(query,URLDecoder.decode(uri.getRawQuery().substring(2),"UTF-8"));
        }
    }
    @Test public void customPathUsesPercentEncodedUtf8NotFormSpaces() throws Exception {
        SearchEngine engine=new SearchEngine("Local","http://localhost:8080/search/%s?lang=ja#results");
        URI uri=new URI(engine.url("a/b c?d"));
        assertEquals("/search/a%2Fb%20c%3Fd",uri.getRawPath());assertEquals("lang=ja",uri.getRawQuery());
        assertEquals("results",uri.getFragment());assertEquals(8080,uri.getPort());
    }
    @Test public void literalPercentIsNotTreatedAsAlreadyEncodedQuery(){
        assertTrue(SearchEngine.GOOGLE.url("%E3%81%82").endsWith("q=%25E3%2581%2582"));
    }
    @Test public void trimsOuterUnicodeWhitespaceButPreservesWords(){
        assertEquals("a  b",SearchEngine.trim("\u3000\u00a0 a  b \t\u3000"));
        invalidQuery("\u3000\u00a0 \n");invalidQuery(null);
    }
    @Test public void destinationCannotBeChangedByQueryOrUseAnActiveScheme(){
        for(String url:new String[]{"javascript:{query}","intent://search/{query}","file:///search/{query}",
                "https://{query}.example.com/search","https://example.com:{query}/search",
                "https://user:password@example.com/?q={query}","https://example.com/#q={query}",
                "https://example.com:65536/?q={query}","https:///search?q={query}","https://example.com/search q={query}"})invalidTemplate(url);
    }
    @Test public void templateRequiresExactlyOneUnambiguousPlaceholder(){
        for(String url:new String[]{"https://example.com/?q=word","https://example.com/?q={query}&other={query}",
                "https://example.com/?q=%s&other={query}",""})invalidTemplate(url);
    }
    @Test public void ordinaryFixedHostsAndIPv6AreNotMistakenForPlaceholders() throws Exception {
        SearchEngine engine=new SearchEngine("Private","https://stellasearchquery.example/search?q={query}&fixed=yes#results");
        assertEquals("stellasearchquery.example",new URI(engine.url("test")).getHost());
        assertEquals("http://[::1]:8080/?q=a%20b",new SearchEngine("Local","http://[::1]:8080/?q={query}").url("a b"));
    }
    @Test public void invalidNamesAreRejectedAndNamesAreTrimmed(){
        assertEquals("Engine",new SearchEngine("\u3000 Engine ","https://example.com/?q={query}").name);
        assertFalse(SearchEngine.validName("\u3000"));assertFalse(SearchEngine.validName("a\nb"));
        assertFalse(SearchEngine.validName(new String(new char[61]).replace('\0','a')));
    }
    private static void invalidTemplate(String template){
        try{new SearchEngine("Test",template);fail("Invalid template was accepted");}catch(IllegalArgumentException expected){}
    }
    private static void invalidQuery(String query){
        try{SearchEngine.GOOGLE.url(query);fail("Blank query was accepted");}catch(IllegalArgumentException expected){}
    }
}
