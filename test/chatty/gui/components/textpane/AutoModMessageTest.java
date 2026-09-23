package chatty.gui.components.textpane;

import chatty.Room;
import chatty.User;
import chatty.util.api.Emoticons;
import chatty.util.api.Emoticons.TagEmotes;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoModMessageTest {
    @Test
    public void routedCopiesKeepEmoteRangesRelativeToTheBody() {
        User user = new User("viewer", Room.createRegular("#testchannel"));
        TagEmotes emotes = Emoticons.parseEmotesTag("112290:10-17");
        AutoModMessage message = new AutoModMessage(user, "Link your imGlitch", "held-id", emotes);
        AutoModMessage copy = message.copy();
        assertSame(emotes, copy.emotes);
        assertSame(user, copy.user);
        assertEquals("held-id", copy.msgId);
        assertEquals(message.message, copy.text.substring(copy.getMsgStart(), copy.getMsgEnd()));
        assertEquals("imGlitch", copy.message.substring(10, copy.emotes.emotes.get(10).end + 1));
        assertNull(new AutoModMessage(user, "imGlitch", "legacy").emotes);
    }
}
