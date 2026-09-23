package chatty.util.api.eventsub.payloads;

import chatty.util.JSONUtil;
import chatty.util.api.Emoticons.TagEmotes;
import chatty.gui.emoji.EmojiUtil;
import chatty.util.api.eventsub.Message;
import chatty.util.api.eventsub.payloads.ModActionPayload.AutoModMessageUpdate;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Exercise the same notification decoder used by EventSub WebSocket messages.
 * Optional reason metadata must never discard an otherwise usable held message.
 */
@SuppressWarnings("unchecked")
public class ModActionPayloadTest {

    private static final String CHANNEL = "testchannel";
    private static final String USER = "ordinaryviewer";
    private static final String MESSAGE_ID = "synthetic-held-message-id";
    private static final String HOLD = "automod.message.hold";
    private static final String UPDATE = "automod.message.update";

    @Test
    public void nativeEmoteFragmentsRetainIdsAndRepeatedPositions() {
        for (String topic : new String[]{HOLD, UPDATE}) {
            JSONObject event = event("blocked_link", "Link your imGlitch account imGlitch");
            ((JSONObject) event.get("message")).put("fragments", array(
                    object("type", "text", "text", "Link your "),
                    emoteFragment("imGlitch", "112290"),
                    object("type", "text", "text", " account "),
                    emoteFragment("imGlitch", "112290")));
            TagEmotes emotes = update(accepted(topic, event)).getEmotes();
            assertEquals(2, emotes.emotes.size());
            assertEquals("112290", emotes.emotes.get(10).id);
            assertEquals(17, emotes.emotes.get(10).end);
            assertEquals("112290", emotes.emotes.get(27).id);
            assertEquals(34, emotes.emotes.get(27).end);
        }
    }

    @Test
    public void emoteOffsetsUseCodePointsIncludingEncodedEmojiJoiners() {
        String prefix = "\uD83D\uDC69" + EmojiUtil.ZWJ_REPLACEMENT + "\uD83D\uDCBB ";
        JSONObject event = event("unknown", prefix + "imGlitch");
        ((JSONObject) event.get("message")).put("fragments", array(
                object("type", "text", "text", prefix), emoteFragment("imGlitch", "112290")));
        AutoModMessageUpdate update = update(accepted(HOLD, event));
        TagEmotes emotes = update.getEmotes();
        assertEquals(11, emotes.emotes.get(4).end);
        String decoded = EmojiUtil.decodeZWJ(update.getMessage());
        assertEquals("imGlitch", decoded.substring(decoded.offsetByCodePoints(0, 4)));
    }

    @Test
    public void identicalLiteralTextIsNotGivenAnotherFragmentsEmoteId() {
        JSONObject event = event("automod", "imGlitch imGlitch");
        ((JSONObject) event.get("message")).put("fragments", array(
                object("type", "text", "text", "imGlitch "), emoteFragment("imGlitch", "112290")));
        TagEmotes emotes = update(accepted(HOLD, event)).getEmotes();
        assertEquals(1, emotes.emotes.size());
        assertFalse(emotes.emotes.containsKey(0));
        assertEquals("112290", emotes.emotes.get(9).id);
    }

    @Test
    public void absentOrMalformedFragmentsKeepTheMessageAndPermitNameFallback() {
        for (Object fragments : new Object[]{null, "bad", object(), array(),
            array(42L), array(object("text", 42L)),
            array(emoteFragment("wrong", "112290")),
            array(emoteFragment("imGlitch", "112290")),
            array(object("text", "imGlitch suffix", "emote", 42L))}) {
            JSONObject event = event("blocked_link", "imGlitch suffix");
            ((JSONObject) event.get("message")).put("fragments", fragments);
            assertNull(update(accepted(HOLD, event)).getEmotes());
        }
        assertNull(update(accepted(HOLD, event("unknown", "imGlitch"))).getEmotes());
    }

    @Test
    public void invalidOptionalEmoteIdsDoNotDiscardLaterValidFragments() {
        for (Object id : new Object[]{null, "", 42L, object()}) {
            JSONObject event = event("unknown", "bad imGlitch");
            ((JSONObject) event.get("message")).put("fragments", array(
                    object("text", "bad", "emote", object("id", id)),
                    object("text", " "), emoteFragment("imGlitch", "112290")));
            TagEmotes emotes = update(accepted(HOLD, event)).getEmotes();
            assertEquals(1, emotes.emotes.size());
            assertEquals("112290", emotes.emotes.get(4).id);
        }
    }

    private static JSONObject emoteFragment(String text, String id) {
        return object("type", "emote", "text", text,
                "emote", object("id", id, "emote_set_id", "0"));
    }

    @Test
    public void unknownBaselineHoldReachesChatPayload() {
        JSONObject event = event("unknown", "A message held by baseline AutoMod");
        ModActionPayload payload = accepted(HOLD, event);
        assertEquals(ModActionPayload.Type.AUTOMOD_FILTERED, payload.type);
        assertEquals("automod_filtered", payload.moderation_action);
        assertEquals("unknown", update(payload).getReason());
    }

    @Test
    public void blockedLinkHoldPreservesUrlAndMessageFragments() {
        String url = "https://example.org/bracket?source=twitch&event=worlds";
        JSONObject event = event("blocked_link", "Check the bracket: " + url);
        JSONObject message = (JSONObject) event.get("message");
        message.put("fragments", array(
                object("type", "text", "text", "Check the bracket: ", "emote", null),
                object("type", "text", "text", url, "emote", null)));
        ModActionPayload payload = accepted(HOLD, event);
        assertEquals("BlockedLink", update(payload).getReason());
        assertTrue(update(payload).getMessage().endsWith(url));
    }

    @Test
    public void automodDetailsUseInclusiveUnicodeCodePointBoundaries() {
        JSONObject event = event("automod", "\uD83D\uDE00 nasty and rude");
        event.put("automod", object("category", "aggression", "level", 2,
                "boundaries", array(boundary(2, 6), boundary(12, 15))));
        assertEquals("AutoMod: aggression2/nasty, rude",
                update(accepted(HOLD, event)).getReason());
    }

    @Test
    public void blockedTermsRetainUnicodeFragmentsAndSharedTermOwners() {
        JSONObject event = event("blocked_term", "\uD83D\uDE00 banned and shared");
        event.put("blocked_term", object("terms_found", array(
                object("boundary", boundary(2, 7), "owner_broadcaster_user_login", CHANNEL),
                object("boundary", boundary(13, 18), "owner_broadcaster_user_login", "otherchannel"))));
        assertEquals("BlockedTerm: banned,shared (from: otherchannel)",
                update(accepted(HOLD, event)).getReason());
    }

    @Test
    public void allHoldReasonsSurviveApprovalDenialAndExpiryUpdates() {
        String[] reasons = {"automod", "blocked_term", "unknown", "blocked_link", "future_reason"};
        String[] statuses = {"approved", "denied", "expired"};
        String[] actions = {"approved_automod_message", "denied_automod_message", "automod_message_expired"};
        ModActionPayload.Type[] types = {ModActionPayload.Type.AUTOMOD_APPROVED,
            ModActionPayload.Type.AUTOMOD_DENIED, ModActionPayload.Type.AUTOMOD_EXPIRED};
        for (String reason : reasons) {
            for (int i = 0; i < statuses.length; i++) {
                JSONObject event = event(reason, "Held message remains identifiable");
                event.put("status", statuses[i]);
                ModActionPayload payload = accepted(UPDATE, event);
                assertEquals(reason + "/" + statuses[i], actions[i], payload.moderation_action);
                assertEquals(actions[i], update(payload).action);
                assertEquals(types[i], payload.type);
                assertEquals("testmoderator", payload.created_by);
                assertEquals(MESSAGE_ID, update(payload).getMsgId());
                if (reason.equals("unknown") || reason.equals("future_reason")) {
                    assertEquals(reason, update(payload).getReason());
                }
                else if (reason.equals("blocked_link")) {
                    assertEquals("BlockedLink", update(payload).getReason());
                }
            }
        }
    }

    @Test
    public void futureReasonAlsoReachesHeldMessagePayload() {
        assertEquals("future_reason", update(accepted(HOLD,
                event("future_reason", "New Twitch reason"))).getReason());
    }

    @Test
    public void updateWithoutModeratorLoginHasSafeActorForDownstreamRouting() {
        for (Object moderator : new Object[]{null, "", 42L}) {
            JSONObject event = event("unknown", "Held message remains identifiable");
            event.put("status", "approved");
            event.put("moderator_user_login", moderator);
            assertEquals("", accepted(UPDATE, event).created_by);
            event.remove("moderator_user_login");
            assertEquals("", accepted(UPDATE, event).created_by);
        }
    }

    @Test
    public void missingNullEmptyAndNonStringReasonsHaveSafeFallbacks() {
        for (String topic : new String[]{HOLD, UPDATE}) {
            for (Object reason : new Object[]{null, "", 42L}) {
                JSONObject event = event(reason, "Optional reason may be unavailable");
                event.put("status", topic.equals(UPDATE) ? "approved" : null);
                assertEquals("AutoMod", update(accepted(topic, event)).getReason());
                event.remove("reason");
                assertEquals("AutoMod", update(accepted(topic, event)).getReason());
                event.put("category", "legacy_category");
                assertEquals("legacy_category", update(accepted(topic, event)).getReason());
            }
        }
    }

    @Test
    public void missingNullAndMalformedAutomodDetailsKeepTheMessage() {
        for (Object details : new Object[]{null, "unexpected", 17L, array(), object()}) {
            JSONObject event = event("automod", "The message matters more than optional metadata");
            event.put("automod", details);
            assertEquals("AutoMod", update(accepted(HOLD, event)).getReason());
            event.remove("automod");
            assertEquals("AutoMod", update(accepted(HOLD, event)).getReason());
        }
    }

    @Test
    public void partialAutomodDetailsRemainReadable() {
        JSONObject event = event("automod", "bad message");
        event.put("automod", object("category", "aggression"));
        assertTrue(update(accepted(HOLD, event)).getReason().contains("aggression"));
        event.put("automod", object("level", 2));
        accepted(HOLD, event);
        event.put("automod", object("boundaries", array(boundary(0, 2))));
        assertTrue(update(accepted(HOLD, event)).getReason().contains("bad"));
    }

    @Test
    public void missingNullAndWrongTypeAutomodBoundaryArraysKeepCategory() {
        for (Object boundaries : new Object[]{null, "unexpected", 17L, object(), array()}) {
            JSONObject event = event("automod", "bad message");
            JSONObject details = object("category", "aggression", "level", 2,
                    "boundaries", boundaries);
            event.put("automod", details);
            assertTrue(update(accepted(HOLD, event)).getReason().contains("aggression"));
            details.remove("boundaries");
            assertTrue(update(accepted(HOLD, event)).getReason().contains("aggression"));
        }
    }

    @Test
    public void malformedAutomodBoundaryEntriesDoNotHideValidFragments() {
        JSONObject event = event("automod", "bad message");
        event.put("automod", object("category", "aggression", "level", 2,
                "boundaries", array(null, "unexpected", 17L, object(),
                        object("start_pos", null, "end_pos", 2),
                        object("start_pos", "0", "end_pos", 2), boundary(0, 2))));
        assertEquals("AutoMod: aggression2/bad", update(accepted(HOLD, event)).getReason());
    }

    @Test
    public void missingNullAndMalformedBlockedTermDetailsKeepTheMessage() {
        for (Object details : new Object[]{null, "unexpected", 17L, array(), object()}) {
            JSONObject event = event("blocked_term", "Blocked term metadata can be absent");
            event.put("blocked_term", details);
            assertEquals("BlockedTerm", update(accepted(HOLD, event)).getReason());
            event.remove("blocked_term");
            assertEquals("BlockedTerm", update(accepted(HOLD, event)).getReason());
        }
    }

    @Test
    public void missingNullAndWrongTypeBlockedTermArraysKeepTheMessage() {
        for (Object terms : new Object[]{null, "unexpected", 17L, object(), array()}) {
            JSONObject event = event("blocked_term", "bad message");
            JSONObject details = object("terms_found", terms);
            event.put("blocked_term", details);
            assertEquals("BlockedTerm", update(accepted(HOLD, event)).getReason());
            details.remove("terms_found");
            assertEquals("BlockedTerm", update(accepted(HOLD, event)).getReason());
        }
    }

    @Test
    public void malformedBlockedTermEntriesDoNotHideValidFragments() {
        JSONObject event = event("blocked_term", "bad message");
        event.put("blocked_term", object("terms_found", array(null, "unexpected", 17L,
                object(), object("boundary", null), object("boundary", "unexpected"),
                object("boundary", object()),
                object("boundary", boundary(0, 2), "owner_broadcaster_user_login", CHANNEL))));
        assertEquals("BlockedTerm: bad", update(accepted(HOLD, event)).getReason());
    }

    @Test
    public void invalidBoundaryPositionsNeverReplaceTheMessageWithAnError() {
        JSONObject[] invalid = {boundary(-1, 2), boundary(3, 1), boundary(0, 11),
            boundary(100, 101), boundary(0, Integer.MAX_VALUE),
            boundary(4294967296L, 4294967298L), boundary(0.5, 2.5)};
        for (JSONObject position : invalid) {
            JSONObject event = event("automod", "bad message");
            event.put("automod", object("boundaries", array(position)));
            assertEquals("Invalid AutoMod boundary: " + position, "AutoMod",
                    update(accepted(HOLD, event)).getReason());
            event.put("reason", "blocked_term");
            event.put("blocked_term", object("terms_found", array(object("boundary", position))));
            assertEquals("Invalid blocked-term boundary: " + position, "BlockedTerm",
                    update(accepted(HOLD, event)).getReason());
        }
    }

    @Test
    public void essentialMessageIdentityAndChannelFieldsMustBePresent() {
        for (String topic : new String[]{HOLD, UPDATE}) {
            for (String field : new String[]{"message_id", "user_login", "broadcaster_user_login"}) {
                for (Object invalid : new Object[]{null, "", 42L}) {
                    JSONObject event = ordinaryAutomodEvent("Otherwise valid message");
                    event.put("status", topic.equals(UPDATE) ? "approved" : null);
                    event.put(field, invalid);
                    rejected(topic, event);
                    event.remove(field);
                    rejected(topic, event);
                }
            }
        }
    }

    @Test
    public void missingEmptyAndMalformedMessageTextMustBeRejected() {
        for (String topic : new String[]{HOLD, UPDATE}) {
            for (Object text : new Object[]{null, "", 42L}) {
                JSONObject event = ordinaryAutomodEvent("Original text");
                event.put("message", object("text", text));
                rejected(topic, event);
                event.put("message", object());
                rejected(topic, event);
            }
            for (Object message : new Object[]{null, "unexpected", 42L, array()}) {
                JSONObject event = ordinaryAutomodEvent("Original text");
                event.put("message", message);
                rejected(topic, event);
                event.remove("message");
                rejected(topic, event);
            }
        }
    }

    @Test
    public void missingNullAndMalformedEventsMustBeRejected() {
        for (String topic : new String[]{HOLD, UPDATE}) {
            for (Object event : new Object[]{null, "unexpected", 42L, array(), object()}) {
                rejected(topic, event);
            }
            JSONObject envelope = envelope(topic, event("unknown", "Original text"));
            ((JSONObject) envelope.get("payload")).remove("event");
            Message message = Message.fromJson(envelope.toJSONString());
            assertTrue(message == null || message.data == null);
        }
    }

    private static ModActionPayload accepted(String topic, JSONObject event) {
        Message message = Message.fromJson(envelope(topic, event).toJSONString());
        assertNotNull("Notification parsing failed: " + topic + " " + event, message);
        assertEquals(topic, message.subType);
        assertEquals("2", message.subVersion);
        assertTrue("Usable AutoMod event was discarded: " + topic + " " + event,
                message.data instanceof ModActionPayload);
        ModActionPayload payload = (ModActionPayload) message.data;
        AutoModMessageUpdate update = update(payload);
        assertEquals(CHANNEL, payload.stream);
        assertEquals(MESSAGE_ID, update.getMsgId());
        assertEquals(USER, update.getUsername());
        assertEquals(((JSONObject) event.get("message")).get("text"), update.getMessage());
        assertTrue(update.isValid());
        assertNotNull("Reason must be renderable", update.getReason());
        assertFalse("Reason must be renderable", update.getReason().isEmpty());
        assertFalse("Reason must not expose missing details", update.getReason().contains("null"));
        assertFalse("Reason must not replace valid content with an error", update.getReason().contains("error, check debug log"));
        assertTrue(payload.getPseudoCommandString().contains(update.getMessage()));
        return payload;
    }

    private static void rejected(String topic, Object event) {
        Message message = Message.fromJson(envelope(topic, event).toJSONString());
        assertTrue("Unusable AutoMod event was accepted: " + topic + " " + event,
                message == null || message.data == null);
    }

    private static AutoModMessageUpdate update(ModActionPayload payload) {
        assertTrue(payload.action instanceof AutoModMessageUpdate);
        return (AutoModMessageUpdate) payload.action;
    }

    private static JSONObject event(Object reason, String text) {
        return object("broadcaster_user_id", "100", "broadcaster_user_login", CHANNEL,
                "broadcaster_user_name", "TestChannel", "user_id", "200", "user_login", USER,
                "user_name", "OrdinaryViewer", "message_id", MESSAGE_ID,
                "moderator_user_id", "300", "moderator_user_login", "testmoderator",
                "moderator_user_name", "TestModerator", "message", object("text", text,
                        "fragments", array(object("type", "text", "text", text,
                                "cheermote", null, "emote", null))),
                "reason", reason, "automod", null, "blocked_term", null,
                "held_at", "2026-09-20T00:00:00.000Z");
    }

    private static JSONObject ordinaryAutomodEvent(String text) {
        JSONObject event = event("automod", text);
        event.put("automod", object("category", "aggression", "level", 2, "boundaries", array()));
        return event;
    }

    private static JSONObject envelope(String topic, Object event) {
        return object("metadata", object("message_id", "synthetic-notification-id",
                "message_type", "notification", "message_timestamp", "2026-09-20T00:00:00.000Z",
                "subscription_type", topic, "subscription_version", "2"),
                "payload", object("subscription", object("id", "synthetic-subscription-id",
                        "status", "enabled", "type", topic, "version", "2",
                        "condition", object("broadcaster_user_id", "100", "moderator_user_id", "300"),
                        "transport", object("method", "websocket", "session_id", "synthetic-session")),
                        "event", event));
    }

    private static JSONObject boundary(Number start, Number end) {
        return object("start_pos", start, "end_pos", end);
    }

    private static JSONObject object(Object... values) {
        return JSONUtil.listMapToJSONObject(values);
    }

    private static JSONArray array(Object... values) {
        JSONArray result = new JSONArray();
        for (Object value : values) {
            result.add(value);
        }
        return result;
    }
}
