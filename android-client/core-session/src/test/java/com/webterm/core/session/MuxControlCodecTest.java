package com.webterm.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.json.JSONObject;
import org.junit.Test;

public class MuxControlCodecTest {
    @Test
    public void connectRoundTripPreservesOwnerAndProtocols() throws Exception {
        MuxControlCodec codec = new MuxControlCodec();
        String encoded = codec.connect("channel-1", "/ws/sessions/s1", "term:s1",
            "device:term:s1", new String[]{"webterm.screen.v2"});
        assertNotNull(encoded);
        JSONObject object = new JSONObject(encoded);
        assertEquals("ws-connect", object.getString("type"));
        assertEquals("device:term:s1", object.getString("channelOwnerKey"));
        assertEquals("webterm.screen.v2", object.getJSONArray("protocols").getString(0));
    }

    @Test
    public void decodeCreatesTypedError() {
        MuxControlCodec.Message message = new MuxControlCodec().decode(
            "{\"type\":\"ws-error\",\"tunnelConnectionId\":\"c1\",\"code\":503,\"message\":\"busy\"}");
        assertNotNull(message);
        assertEquals("c1", message.channelId);
        assertEquals(503, message.code);
        assertEquals("busy", message.message);
    }
}
