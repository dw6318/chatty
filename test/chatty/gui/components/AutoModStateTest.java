package chatty.gui.components;

import chatty.gui.components.AutoModDialog.Item;
import chatty.util.api.TwitchApi.AutoModAction;
import chatty.util.api.TwitchApi.AutoModActionResult;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoModStateTest {
    @Test
    public void pendingAndCompletedItemsCannotBeSubmittedAgain() {
        Item item = new Item(null, null);
        assertTrue(item.beginRequest());
        assertFalse(item.beginRequest());
        assertFalse(item.canRequestAction());
        item.applyRequestResult(AutoModAction.ALLOW, AutoModActionResult.SUCCESS);
        assertEquals("Approved", item.getStatusText());
        assertFalse(item.hasRequestPending());
        assertFalse(item.beginRequest());
    }

    @Test
    public void failedActionsRemainRetryableAndRetryCanSucceed() {
        Item item = new Item(null, null);
        item.beginRequest();
        item.applyRequestResult(AutoModAction.ALLOW, AutoModActionResult.OTHER_ERROR);
        assertEquals("Error", item.getStatusText());
        assertTrue(item.canRequestAction());
        assertTrue(item.beginRequest());
        item.applyRequestResult(AutoModAction.DENY, AutoModActionResult.SUCCESS);
        assertEquals("Denied", item.getStatusText());
    }

    @Test
    public void confirmedServerOutcomeWinsOverLateSuccessOrFailure() {
        for (AutoModActionResult result : AutoModActionResult.values()) {
            Item item = new Item(null, null);
            item.beginRequest();
            item.applyExternalStatus(Item.STATUS_DENIED, "othermod");
            item.applyRequestResult(AutoModAction.ALLOW, result);
            assertEquals("Denied", item.getStatusText());
            assertEquals("othermod", item.getHandledBy());
            assertFalse(item.hasRequestPending());
            assertFalse(item.canRequestAction());
        }
    }

    @Test
    public void laterServerEventConfirmsActorAndOverridesLocalResult() {
        Item item = new Item(null, null);
        item.beginRequest();
        item.applyRequestResult(AutoModAction.ALLOW, AutoModActionResult.SUCCESS);
        item.applyExternalStatus(Item.STATUS_DENIED, "othermod");
        assertEquals("Denied", item.getStatusText());
        assertEquals("othermod", item.getHandledBy());
        item.applyExternalStatus(Item.STATUS_DENIED, "duplicate");
        assertEquals("othermod", item.getHandledBy());
    }

    @Test
    public void expiryIsTerminalEvenWhenAnActionWasPending() {
        Item item = new Item(null, null);
        item.beginRequest();
        item.applyExternalStatus(Item.STATUS_EXPIRED, "");
        item.applyRequestResult(AutoModAction.ALLOW, AutoModActionResult.SUCCESS);
        assertEquals("Expired", item.getStatusText());
        assertNull(item.getHandledBy());
        assertTrue(item.isHandled());
        assertFalse(item.beginRequest());
    }

    @Test
    public void alreadyHandledOrMissingMessagesAreTerminal() {
        for (AutoModActionResult result : new AutoModActionResult[]{
                AutoModActionResult.ALREADY_PROCESSED, AutoModActionResult.NOT_FOUND}) {
            Item item = new Item(null, null);
            item.beginRequest();
            item.applyRequestResult(AutoModAction.ALLOW, result);
            assertTrue(item.isHandled());
            assertFalse(item.canRequestAction());
            // A later authoritative event can still supply the actual outcome.
            item.applyExternalStatus(Item.STATUS_APPROVED, "mod");
            assertEquals("Approved", item.getStatusText());
        }
    }
}
