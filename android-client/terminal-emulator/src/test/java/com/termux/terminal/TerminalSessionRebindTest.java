package com.termux.terminal;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class TerminalSessionRebindTest extends TestCase {

    private static final class RecordingSessionClient implements TerminalSessionClient {
        @Override
        public void onTextChanged(TerminalSession session) { }

        @Override
        public void onTitleChanged(TerminalSession session) { }

        @Override
        public void onSessionFinished(TerminalSession session) { }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) { }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) { }

        @Override
        public void onBell(TerminalSession session) { }

        @Override
        public void onColorsChanged(TerminalSession session) { }

        @Override
        public void onTerminalCursorStateChange(boolean state) { }

        @Override
        public void setTerminalShellPid(TerminalSession session, int pid) { }

        @Override
        public Integer getTerminalCursorStyle() { return null; }

        @Override
        public void logError(String tag, String message) { }

        @Override
        public void logWarn(String tag, String message) { }

        @Override
        public void logInfo(String tag, String message) { }

        @Override
        public void logDebug(String tag, String message) { }

        @Override
        public void logVerbose(String tag, String message) { }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) { }

        @Override
        public void logStackTrace(String tag, Exception e) { }
    }

    private static final class RecordingIOClient implements TerminalSession.ExternalIOClient {
        final List<String> inputs = new ArrayList<>();
        final List<String> resizes = new ArrayList<>();

        @Override
        public void onTerminalInput(String data) {
            inputs.add(data);
        }

        @Override
        public void onTerminalResize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
            resizes.add(columns + "x" + rows);
        }
    }

    public void testRebindingExternalIOClientRoutesInputToNewCallback() {
        RecordingIOClient oldClient = new RecordingIOClient();
        RecordingIOClient newClient = new RecordingIOClient();
        TerminalSession session = TerminalSession.createExternalSession(100, new RecordingSessionClient(), oldClient);

        session.write("before");
        assertEquals(1, oldClient.inputs.size());
        assertEquals("before", oldClient.inputs.get(0));
        assertTrue(newClient.inputs.isEmpty());

        session.updateExternalIOClient(newClient);
        session.write("after");
        assertEquals(1, oldClient.inputs.size());
        assertEquals(1, newClient.inputs.size());
        assertEquals("after", newClient.inputs.get(0));
    }

    public void testRebindingExternalIOClientRoutesResizeToNewCallback() {
        RecordingIOClient oldClient = new RecordingIOClient();
        RecordingIOClient newClient = new RecordingIOClient();
        TerminalSession session = TerminalSession.createExternalSession(100, new RecordingSessionClient(), oldClient);

        session.updateSize(80, 24, 13, 15);
        assertEquals(1, oldClient.resizes.size());
        assertTrue(newClient.resizes.isEmpty());

        session.updateExternalIOClient(newClient);
        session.updateSize(80, 25, 13, 15);
        assertEquals(1, oldClient.resizes.size());
        assertEquals(1, newClient.resizes.size());
        assertEquals("80x25", newClient.resizes.get(0));
    }
}
