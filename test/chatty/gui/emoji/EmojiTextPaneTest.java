package chatty.gui.emoji;

import chatty.util.api.Emoticon;
import java.awt.Font;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.swing.text.Document;
import javax.swing.SwingUtilities;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmojiTextPaneTest {
    private static Set<Emoticon> emoji;

    @BeforeClass
    public static void loadEmoji() {
        emoji = new TreeSet<>((a, b) -> {
            int size = Integer.compare(b.code.length(), a.code.length());
            return size != 0 ? size : a.code.compareTo(b.code);
        });
        emoji.addAll(EmojiUtil.makeEmoticons("twemoji"));
    }

    @Test
    public void rendersNewEmojiAndWholeJoinedSequencesWithoutChangingText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EmojiTextPane pane = new EmojiTextPane();
            pane.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
            // Unicode 15, 15.1, 16, 17, a skin tone and a qualified multi-part sequence.
            String[] examples = {"\uD83E\uDEE8", "\uD83D\uDE42\u200D\u2194\uFE0F",
                "\uD83E\uDEE9", "\uD83E\uDEEA", "\uD83D\uDC4D\uD83C\uDFFD",
                "\uD83D\uDC69\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68"};
            String text = "[AutoMod] <viewer> " + String.join(" ", examples);
            pane.setEmojiText(text, emoji);
            assertEquals(text, pane.getText());
            int start = text.indexOf(examples[0]);
            for (String example : examples) {
                Element element = pane.getStyledDocument().getCharacterElement(start);
                assertNotNull("Missing emoji image", StyleConstants.getIcon(element.getAttributes()));
                assertEquals(start, element.getStartOffset());
                assertEquals(start + example.length(), element.getEndOffset());
                start += example.length() + 1;
            }
            pane.setSize(250, Short.MAX_VALUE);
            assertTrue("AutoMod text must wrap", pane.getPreferredSize().height > 30);
        });
    }

    @Test
    public void respectsTextPresentationAndKeepsRepeatedImagesSeparate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EmojiTextPane pane = new EmojiTextPane();
            pane.setEmojiText("\u2764\uFE0E \uD83E\uDEE8\uD83E\uDEE8", emoji);
            assertNull(StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes()));
            Element first = pane.getStyledDocument().getCharacterElement(3);
            Element second = pane.getStyledDocument().getCharacterElement(5);
            assertNotNull(StyleConstants.getIcon(first.getAttributes()));
            assertNotNull(StyleConstants.getIcon(second.getAttributes()));
            assertNotSame(first, second);
        });
    }

    @Test
    public void disablingImagesAndReusingTheRendererClearsOldIcons() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EmojiTextPane pane = new EmojiTextPane();
            String text = "\uD83E\uDEE8";
            pane.setEmojiText(text, emoji);
            pane.setEmojiText(text, emoji, emote -> false);
            assertNull(StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes()));
            pane.setEmojiText(text, Collections.emptySet());
            assertEquals(text, pane.getText());
            assertNull(StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes()));
            pane.setEmojiText("plain text", emoji);
            assertEquals("plain text", pane.getText());
        });
    }

    @Test
    public void switchingArtworkKeepsSelectedAndFallbackImagesVisible() throws Exception {
        for (String style : new String[]{"fluent", "noto", "openmoji"}) {
            Set<Emoticon> selected = new TreeSet<>(((TreeSet<Emoticon>) emoji).comparator());
            selected.addAll(EmojiUtil.makeEmoticons(style));
            SwingUtilities.invokeAndWait(() -> {
                EmojiTextPane pane = new EmojiTextPane();
                String text = "\uD83D\uDE00 \uD83E\uDEEA";
                pane.setEmojiText(text, emoji);
                Object twemojiFace = StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes());
                pane.setEmojiText(text, selected);
                assertEquals(text, pane.getText());
                Object selectedFace = StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes());
                assertNotNull(selectedFace);
                assertNotSame("Changing the set must replace cached artwork", twemojiFace, selectedFace);
                assertNotNull(StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(3).getAttributes()));
            });
        }
    }

    @Test
    public void cachedRowsAndChangingPrefixesReuseBodyMatches() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EmojiTextPane pane = new EmojiTextPane();
            AtomicInteger checks = new AtomicInteger();
            Predicate<Emoticon> allowed = e -> { checks.incrementAndGet(); return true; };
            String body = "hello \uD83D\uDE00\uD83D\uDE00";
            pane.setCachedEmojiText("[now] ", body, emoji, allowed, 1);
            Document original = pane.getDocument();
            int initialChecks = checks.get();
            assertTrue(initialChecks > 0);
            pane.setCachedEmojiText("[now] ", "other \uD83E\uDEE8", emoji, allowed, 1);
            int otherChecks = checks.get();
            pane.setCachedEmojiText("[now] ", body, emoji, allowed, 1);
            assertSame(original, pane.getDocument());
            assertEquals(otherChecks, checks.get());
            pane.setCachedEmojiText("-Approved- [1m] ", body, emoji, allowed, 1);
            assertEquals(otherChecks, checks.get());
            assertEquals("-Approved- [1m] " + body, pane.getText());
            int first = pane.getText().indexOf("\uD83D\uDE00");
            Element a = pane.getStyledDocument().getCharacterElement(first);
            Element b = pane.getStyledDocument().getCharacterElement(first + 2);
            assertNotNull(StyleConstants.getIcon(a.getAttributes()));
            assertNotSame(a, b);
            pane.setSize(150, Short.MAX_VALUE);
            assertTrue(pane.getPreferredSize().height > 30);
        });
    }

    @Test
    public void cachedRowsInvalidateOnPreferencesCatalogAndFontChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EmojiTextPane pane = new EmojiTextPane();
            String text = "\uD83D\uDE00";
            pane.setCachedEmojiText("", text, emoji, e -> true, 1);
            Document first = pane.getDocument();
            int size = StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes()).getIconHeight();
            pane.setFont(pane.getFont().deriveFont(30f));
            pane.setCachedEmojiText("", text, emoji, e -> true, 1);
            assertNotSame(first, pane.getDocument());
            assertTrue(StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes()).getIconHeight() > size);
            pane.setCachedEmojiText("", text, emoji, e -> false, 2);
            assertNull(StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes()));
            pane.setCachedEmojiText("", text, Collections.emptySet(), e -> true, 2);
            assertNull(StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes()));
            pane.setCachedEmojiText("", text, emoji, e -> true, 2);
            assertNotNull(StyleConstants.getIcon(pane.getStyledDocument().getCharacterElement(0).getAttributes()));
        });
    }

    @Test
    public void rowCacheEvictsOldMessagesAndUncachedFormattingDoesNotCorruptIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            EmojiTextPane pane = new EmojiTextPane();
            pane.setCachedEmojiText("", "first", emoji, e -> true, 0);
            Document first = pane.getDocument();
            pane.setEmojiText("uncached", emoji);
            pane.setCachedEmojiText("", "first", emoji, e -> true, 0);
            assertSame(first, pane.getDocument());
            assertEquals("first", pane.getText());
            for (int i = 0; i < 50; i++) {
                pane.setCachedEmojiText("", "row " + i, emoji, e -> true, 0);
            }
            pane.setCachedEmojiText("", "first", emoji, e -> true, 0);
            assertNotSame(first, pane.getDocument());
            assertEquals("first", pane.getText());
        });
    }
}
