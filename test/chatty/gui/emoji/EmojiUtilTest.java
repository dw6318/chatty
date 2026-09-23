
package chatty.gui.emoji;

import chatty.util.api.Emoticon;
import chatty.util.api.CachedImage.ImageType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.net.URL;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author tduva
 */
public class EmojiUtilTest {

    @Test
    public void coversUnicode17DisplaySequences() throws Exception {
        Set<String> codes = new HashSet<>();
        for (Emoticon emoji : EmojiUtil.makeEmoticons("twemoji")) {
            codes.add(emoji.code);
        }
        int checked = 0;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("emoji-test-17.0.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                StringBuilder code = new StringBuilder();
                for (String point : line.substring(0, line.indexOf(';')).trim().split(" +")) {
                    code.appendCodePoint(Integer.parseInt(point, 16));
                }
                assertTrue("No bundled artwork for " + line, codes.contains(code.toString()));
                assertTrue("Emoji prefilter misses " + line, EmojiUtil.mightContainEmoji(code.toString()));
                checked++;
            }
        }
        assertTrue("Unicode reference data was not fully read", checked > 5000);
    }

    @Test
    public void noEmojiSelectionRemainsSupported() {
        assertTrue(EmojiUtil.makeEmoticons("none").isEmpty());
        assertTrue(EmojiUtil.makeEmoticons(null).isEmpty());
    }

    @Test
    public void selectableStylesRetainCoverageShortcodesAndReadableArtwork() throws Exception {
        Map<String, Emoticon> twemoji = new HashMap<>();
        for (Emoticon emoji : EmojiUtil.makeEmoticons("twemoji")) {
            twemoji.put(emoji.code, emoji);
        }
        for (String style : new String[]{"fluent", "noto", "openmoji"}) {
            Set<Emoticon> selected = EmojiUtil.makeEmoticons(style);
            assertEquals(twemoji.size(), selected.size());
            Set<String> images = new HashSet<>();
            int selectedCount = 0;
            for (Emoticon emoji : selected) {
                Emoticon original = twemoji.get(emoji.code);
                assertNotNull(style + " lost a Unicode sequence", original);
                assertEquals(original.stringId, emoji.stringId);
                assertEquals(original.stringIdAlias, emoji.stringIdAlias);
                assertEquals(original.getInfos(), emoji.getInfos());
                String image = emoji.getEmoteUrl(1, ImageType.STATIC);
                if (image.contains("/" + style + "/72x72/")) {
                    selectedCount++;
                }
                assertFalse(image.contains("/apple/"));
                if (images.add(image)) {
                    assertNotNull("Unreadable bundled PNG: " + image, ImageIO.read(new URL(image)));
                }
                // A common face must use the selected style, not just fallback.
                if (emoji.code.equals("\uD83D\uDE00")) {
                    assertTrue(image.contains("/" + style + "/72x72/"));
                }
            }
            assertTrue(style + " artwork catalog was not fully loaded", selectedCount > 2000);
        }
    }

    @Test
    public void legacyPrivatePreferenceUsesTwemojiWithoutAppleAssets() {
        Map<String, String> urls = new HashMap<>();
        for (Emoticon emoji : EmojiUtil.makeEmoticons("twemoji")) {
            urls.put(emoji.code, emoji.getEmoteUrl(1, ImageType.STATIC));
        }
        Set<Emoticon> migrated = EmojiUtil.makeEmoticons("apple");
        assertEquals(urls.size(), migrated.size());
        for (Emoticon emoji : migrated) {
            assertEquals(urls.get(emoji.code), emoji.getEmoteUrl(1, ImageType.STATIC));
        }
        assertNull(EmojiUtil.class.getResource("apple/emoji.tsv"));
    }
    
    /**
     * Test that the function to check for possible Emoji actually matches all
     * Emoji.
     */
    @Test
    public void testContainsEmoji() {
        for (EmojiUtil.EmojiSet set : EmojiUtil.EmojiSet.values()) {
            Set<Emoticon> emotes = EmojiUtil.makeEmoticons(set.id);
            for (Emoticon emoji : emotes) {
                assertTrue("Failed to detect "+emoji.stringId+" ("+emoji.code+")",
                        EmojiUtil.mightContainEmoji(emoji.code));
            }
        }
    }
    
    /**
     * Test that each Emoji shortcode (including aliases) is only used once.
     */
    @Test
    public void testShortCodeUnique() {
        for (EmojiUtil.EmojiSet set : EmojiUtil.EmojiSet.values()) {
            Set<String> shortCodes = new HashSet<>();
            Set<Emoticon> emotes = EmojiUtil.makeEmoticons(set.id);
            for (Emoticon emoji : emotes) {
                assertFalse("Duplicate shortcode "+emoji.stringId,
                        shortCodes.contains(emoji.stringId));
                assertFalse("Duplicate shortcode "+emoji.stringIdAlias,
                        shortCodes.contains(emoji.stringIdAlias));
                if (emoji.stringId != null) {
                    shortCodes.add(emoji.stringId);
                }
                if (emoji.stringIdAlias != null) {
                    shortCodes.add(emoji.stringIdAlias);
                }
            }
        }
    }
    
}
