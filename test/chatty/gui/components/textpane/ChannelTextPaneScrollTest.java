package chatty.gui.components.textpane;

import chatty.gui.MainGui;
import chatty.gui.StyleServer;
import chatty.util.Timestamp;
import chatty.util.colors.ColorCorrector;
import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.swing.DefaultBoundedRangeModel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real scroll manager without launching account/profile startup. */
public class ChannelTextPaneScrollTest {
    private interface Check { void run(Fixture f) throws Exception; }

    private void check(Check check) throws Exception { check(false, check); }

    private void check(boolean insertTop, Check check) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = null;
            try {
                f = new Fixture(insertTop);
                check.run(f);
            }
            catch (Exception ex) { throw new RuntimeException(ex); }
            finally { if (f != null) f.pane.cleanUp(); }
        });
    }

    @Test
    public void pendingAutoScrollCanPauseBeforeLayoutCatchesUp() throws Exception {
        check(f -> {
            f.followBehind();
            f.move();
            assertTrue("Following chat must pause even while layout is behind", f.flag("fixedChat"));
        });
    }

    @Test
    public void pendingAutoScrollCanPauseWithNewestMessagesAtTop() throws Exception {
        check(true, f -> {
            f.followBehind();
            f.move();
            assertTrue(f.flag("fixedChat"));
        });
    }

    @Test
    public void browsingOlderMessagesDoesNotStartHoverPause() throws Exception {
        check(f -> {
            f.followBehind();
            f.set("scrollDownRequest", false);
            f.move();
            assertFalse(f.flag("fixedChat"));
        });
    }

    @Test
    public void layoutPositionCorrectionDoesNotCancelAnActivePause() throws Exception {
        check(f -> {
            f.atEnd(); f.move();
            assertTrue(f.flag("fixedChat"));
            f.bar.setMaximum(1400);
            // A viewport/layout correction arrives with unchanged maximum/extent.
            // This is not an input event, but used to be classified as manual.
            f.bar.setValue(850);
            assertTrue("Layout must not cancel hover pause", f.flag("fixedChat"));
            f.move();
            assertTrue(f.flag("fixedChat"));
        });
    }

    @Test
    public void draggingScrollbarStillCancelsPauseAndFollowing() throws Exception {
        check(f -> {
            f.atEnd(); f.move();
            f.bar.setValueIsAdjusting(true);
            f.bar.setValue(500);
            assertFalse(f.flag("fixedChat"));
            assertFalse(f.flag("scrollDownRequest"));
            f.bar.setValueIsAdjusting(false);
        });
    }

    @Test
    public void realMouseWheelEventStillCancelsPauseAndFollowing() throws Exception {
        Fixture[] fixture = new Fixture[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Fixture f = new Fixture(false); fixture[0] = f;
                    f.atEnd(); f.move();
                    assertTrue(f.flag("fixedChat"));
                    Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new MouseWheelEvent(
                            f.scroll, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                            20, 20, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1));
                }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
            SwingUtilities.invokeAndWait(() -> {
                try {
                    assertTrue("Wheel must actually move the scrollbar", fixture[0].bar.getValue() < 900);
                    assertFalse(fixture[0].flag("fixedChat"));
                    assertFalse(fixture[0].flag("scrollDownRequest"));
                }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
        }
        finally {
            SwingUtilities.invokeAndWait(() -> { if (fixture[0] != null) fixture[0].pane.cleanUp(); });
        }
    }

    @Test
    public void hiddenScrollbarDoesNotTrapPausedState() throws Exception {
        check(f -> {
            f.atEnd(); f.move();
            f.bar.setVisible(false);
            f.call("setFixedChat", new Class<?>[]{boolean.class}, false);
            assertFalse(f.flag("fixedChat"));
        });
    }

    @Test
    public void scrollingExceptionDoesNotLeaveReentrancyGuardSet() throws Exception {
        check(f -> {
            f.atEnd();
            f.bar.fail = true;
            try {
                f.call("scrollDown", new Class<?>[0]);
                fail("Expected synthetic scrollbar failure");
            }
            catch (InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
            finally { f.bar.fail = false; }
            assertFalse("A failed layout must not poison future scroll events", f.flag("scrollingDownInProgress"));
            f.call("scrollDown", new Class<?>[0]);
            f.bar.setValue(500);
            assertFalse(f.flag("scrollDownRequest"));
        });
    }

    @Test
    public void heldControlKeepsPauseEvenWithoutKeyRepeat() throws Exception {
        check(f -> {
            f.atEnd(); f.move();
            f.call("handleKeyPressed", new Class<?>[0]);
            f.set("mouseLastMoved", System.currentTimeMillis() - 5000);
            f.tick();
            assertTrue("Holding Ctrl must not depend on OS key repeat", f.flag("fixedChat"));
            f.call("handleKeyReleased", new Class<?>[0]);
            f.set("mouseLastMoved", System.currentTimeMillis() - 5000);
            f.tick();
            assertFalse(f.flag("fixedChat"));
        });
    }

    @Test
    public void nextMouseMoveRecoversMissedControlRelease() throws Exception {
        check(f -> {
            f.atEnd(); f.move();
            f.call("handleKeyPressed", new Class<?>[0]);
            f.move(); // No Ctrl modifier: release was delivered elsewhere.
            assertFalse(f.flag("pauseKeyPressed"));
            f.set("mouseLastMoved", System.currentTimeMillis() - 5000);
            f.tick();
            assertFalse(f.flag("fixedChat"));
        });
    }

    @Test
    public void timerSurvivesScrollFailureAndNextPauseStillExpires() throws Exception {
        check(f -> {
            f.atEnd(); f.move();
            f.set("mouseLastMoved", System.currentTimeMillis() - 5000);
            f.bar.fail = true;
            try { f.tick(); }
            finally { f.bar.fail = false; }
            assertFalse(f.flag("fixedChat"));
            assertFalse(f.flag("scrollingDownInProgress"));
            f.move();
            assertTrue(f.flag("fixedChat"));
            f.set("mouseLastMoved", System.currentTimeMillis() - 5000);
            f.tick();
            assertFalse(f.flag("fixedChat"));
        });
    }

    @Test
    public void inactivityResumesAndHoverCanPauseAgain() throws Exception {
        check(f -> {
            for (int i = 0; i < 100; i++) {
                f.atEnd(); f.move();
                assertTrue(f.flag("fixedChat"));
                f.bar.setMaximum(1400);
                f.set("mouseLastMoved", System.currentTimeMillis() - 5000);
                f.tick();
                assertFalse(f.flag("fixedChat"));
                assertEquals(f.bar.getMaximum() - f.bar.getVisibleAmount(), f.bar.getValue());
            }
        });
    }

    private static final class QuietRangeModel extends DefaultBoundedRangeModel {
        boolean quiet;
        @Override protected void fireStateChanged() { if (!quiet) super.fireStateChanged(); }
    }

    private static final class TestBar extends JScrollBar {
        boolean fail;
        @Override public void setValue(int value) {
            if (fail) throw new IllegalStateException("Synthetic scrollbar failure");
            super.setValue(value);
        }
    }

    private static final class Fixture {
        final ChannelTextPane pane;
        final Object manager;
        final TestBar bar = new TestBar();
        final QuietRangeModel model = new QuietRangeModel();
        final JScrollPane scroll = new JScrollPane();
        final boolean insertTop;

        Fixture(boolean insertTop) throws Exception {
            this.insertTop = insertTop;
            // Only getUserListener() is needed from this shell; no GUI/account
            // constructor, settings files, network requests or hidden windows.
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe"); field.setAccessible(true);
            Object unsafe = field.get(null);
            MainGui shell = (MainGui) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, MainGui.class);
            pane = new ChannelTextPane(shell, new TestStyles(), ChannelTextPane.Type.REGULAR, false, insertTop);
            bar.setModel(model);
            scroll.setVerticalScrollBar(bar);
            pane.setScrollPane(scroll);
            bar.setVisible(true);
            Field managerField = ChannelTextPane.class.getDeclaredField("scrollManager");
            managerField.setAccessible(true); manager = managerField.get(pane);
        }

        void atEnd() throws Exception {
            bar.setValues(insertTop ? 0 : 900, 100, 0, 1000);
            set("scrollDownRequest", true);
        }
        void followBehind() throws Exception {
            atEnd();
            // Model geometry can change before the next layout notification.
            model.quiet = true;
            try { model.setRangeProperties(500, 100, 0, 1400, false); }
            finally { model.quiet = false; }
        }
        void move() {
            ((MouseMotionListener) manager).mouseMoved(new MouseEvent(pane,
                    MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 30, 30, 0, false));
        }
        boolean flag(String name) throws Exception { return (Boolean) get(name); }
        Object get(String name) throws Exception {
            Field field = manager.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(manager);
        }
        void set(String name, Object value) throws Exception {
            Field field = manager.getClass().getDeclaredField(name); field.setAccessible(true); field.set(manager, value);
        }
        Object call(String name, Class<?>[] types, Object... args) throws Exception {
            Method method = manager.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(manager, args);
        }
        void tick() throws Exception {
            Timer timer = (Timer) get("updateTimer");
            for (ActionListener listener : timer.getActionListeners()) listener.actionPerformed(new ActionEvent(timer, 0, "test"));
        }
    }

    private static final class TestStyles implements StyleServer {
        @Override public Color getColor(String name) { return Color.BLACK; }
        @Override public Font getFont(String name) { return new Font("Dialog", Font.PLAIN, 14); }
        @Override public Timestamp getTimestampFormat() { return null; }
        @Override public ColorCorrector getColorCorrector() { return ColorCorrector.get("off"); }
        @Override public MutableAttributeSet getStyle(String name) {
            SimpleAttributeSet style = new SimpleAttributeSet();
            StyleConstants.setFontFamily(style, "Dialog");
            StyleConstants.setFontSize(style, 14);
            style.addAttribute(ChannelTextPane.Attribute.PARAGRAPH_SPACING, 0L);
            MyStyleConstants.setFontHeight(style, 18);
            return style;
        }
    }
}
