package chatty.gui.emoji;

import chatty.util.api.CachedImage.ImageType;
import chatty.util.api.Emoticon;
import java.awt.Graphics2D;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JTextPane;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyleConstants;

/** A wrapping, read-only text component with bundled emoji images. Use on the EDT. */
public class EmojiTextPane extends JTextPane {

    private static final Logger LOGGER = Logger.getLogger(EmojiTextPane.class.getName());
    private static final int ROW_CACHE_LIMIT = 40;
    private final Map<String, StyledDocument> rows = boundedCache(ROW_CACHE_LIMIT);
    private final Map<String, List<Span>> parsedBodies = boundedCache(ROW_CACHE_LIMIT);
    private Iterable<Emoticon> cachedCatalog;
    private long cachedRevision;
    private Font cachedFont;
    private final Map<String, Icon> icons = new LinkedHashMap<String, Icon>(32, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Icon> entry) {
            return size() > 128;
        }
    };

    public EmojiTextPane() {
        setEditable(false);
        setFocusable(false);
    }

    /** Keeps the original Unicode text in the document for copying and accessibility.
     * @param emoji Emoji collection ordered longest sequence first, as returned by Emoticons.getEmoji().
     */
    public void setEmojiText(String text, Iterable<Emoticon> emoji) {
        setEmojiText(text, emoji, emote -> true);
    }

    public void setEmojiText(String text, Iterable<Emoticon> emoji, Predicate<Emoticon> allowed) {
        formatText("", text, findSpans(text, emoji, allowed));
    }

    /**
     * Reuse read-only row documents and body matches across list repaints. Call
     * on the EDT, and change revision whenever the catalog or ignore rules change.
     * Prefix contains the changing age/status; body is the held message text.
     */
    public void setCachedEmojiText(String prefix, String body, Iterable<Emoticon> emoji,
            Predicate<Emoticon> allowed, long revision) {
        if (cachedCatalog != emoji || cachedRevision != revision || !Objects.equals(cachedFont, getFont())) {
            rows.clear();
            parsedBodies.clear();
            cachedCatalog = emoji;
            cachedRevision = revision;
            cachedFont = getFont();
        }
        String text = prefix + body;
        StyledDocument row = rows.get(text);
        if (row == null) {
            List<Span> spans = parsedBodies.get(body);
            if (spans == null) {
                spans = findSpans(body, emoji, allowed);
                parsedBodies.put(body, spans);
            }
            formatText(prefix, body, spans);
            row = getStyledDocument();
            rows.put(text, row);
        }
        else if (getDocument() != row) {
            setStyledDocument(row);
        }
    }

    private void formatText(String prefix, String text, List<Span> spans) {
        // Never mutate a document that another cached row may still reference.
        setStyledDocument(new DefaultStyledDocument());
        setText(prefix + (text == null ? "" : text));
        for (Span span : spans) {
            SimpleAttributeSet style = new SimpleAttributeSet();
            StyleConstants.setIcon(style, span.icon);
            int start = prefix.length() + span.start;
            style.addAttribute("emoji-start", start);
            getStyledDocument().setCharacterAttributes(start, span.end - span.start, style, false);
        }
    }

    private List<Span> findSpans(String text, Iterable<Emoticon> emoji, Predicate<Emoticon> allowed) {
        List<Span> spans = new ArrayList<>();
        if (text == null || !EmojiUtil.mightContainEmoji(text)) {
            return spans;
        }
        int size = Math.max(16, getFontMetrics(getFont()).getHeight());
        BitSet used = new BitSet(text.length());
        for (Emoticon emote : emoji) {
            if (!allowed.test(emote)) {
                continue;
            }
            Matcher matcher = emote.getMatcher(text);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                int occupied = used.nextSetBit(start);
                if (matcher.group().endsWith("\uFE0E") || (occupied >= 0 && occupied < end)) {
                    continue;
                }
                Icon icon = getEmojiIcon(emote, size);
                if (icon != null) {
                    spans.add(new Span(start, end, icon));
                    used.set(start, end);
                }
            }
        }
        return spans;
    }

    private static <K, V> Map<K, V> boundedCache(int limit) {
        return new LinkedHashMap<K, V>(limit, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> entry) {
                return size() > limit;
            }
        };
    }

    private static class Span {
        final int start;
        final int end;
        final Icon icon;

        Span(int start, int end, Icon icon) {
            this.start = start;
            this.end = end;
            this.icon = icon;
        }
    }

    private Icon getEmojiIcon(Emoticon emoji, int size) {
        String path = emoji.getEmoteUrl(1, ImageType.STATIC);
        String key = path + ":" + size;
        if (!icons.containsKey(key)) {
            Icon icon = null;
            try {
                // Emoji artwork is bundled; this component never fetches remote images.
                URL url = new URL(path);
                if (!"file".equals(url.getProtocol()) && !path.startsWith("jar:file:")) {
                    return null;
                }
                BufferedImage source = ImageIO.read(url);
                if (source != null) {
                    BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = scaled.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(source, 0, 0, size, size, null);
                    }
                    finally {
                        graphics.dispose();
                    }
                    icon = new ImageIcon(scaled);
                }
            }
            catch (IOException ex) {
                LOGGER.warning("Unable to load bundled emoji: " + ex);
            }
            icons.put(key, icon);
        }
        return icons.get(key);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }
}
