package com.webterm.mobile.diagnostics;

import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.HistoryExtent;
import com.webterm.terminal.model.ScreenPatchV2;
import com.webterm.terminal.model.ScreenBaseline;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalStateUpdate;
import com.webterm.terminal.model.capture.CapturedModelState;
import com.webterm.terminal.model.capture.CapturedViewState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * 把 Android 终端域对象序列化为 schema 稳定的 JSON，供现场包文件使用。
 * 字段含义与 Agent 端 terminalcapture.FrameToJSON 对齐（lineId/version/wrapped/cells、
 * cursor、styles、links、revision），便于离线对比工具跨阶段比对。仅在导出时（捕获后台
 * 线程）调用，绝不在热路径执行。
 */
final class CaptureSerializer {
    private CaptureSerializer() {}

    static JSONObject cell(TerminalCell c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("text", c.text);
        o.put("width", c.width);
        if (c.style != null) {
            JSONObject resolved = new JSONObject();
            resolved.put("fg", color(c.style.fg));
            resolved.put("bg", color(c.style.bg));
            resolved.put("ulColor", color(c.style.underlineColor));
            resolved.put("attrs", c.style.attrs);
            o.put("resolvedStyle", resolved);
        }
        if (c.link != null) o.put("resolvedLinkUri", c.link.uri);
        return o;
    }

    static JSONObject line(TerminalLine line) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("lineId", line.id);
        o.put("version", line.version);
        o.put("historySeq", line.historySeq);
        o.put("wrapped", line.wrapped);
        JSONArray cells = new JSONArray();
        if (line.cells != null) {
            for (TerminalCell c : line.cells) {
                cells.put(cell(c));
            }
        }
        o.put("cells", cells);
        return o;
    }

    static JSONArray lines(TerminalLine[] lines) throws JSONException {
        JSONArray arr = new JSONArray();
        if (lines != null) {
            for (TerminalLine l : lines) arr.put(line(l));
        }
        return arr;
    }

    static JSONArray lineList(List<TerminalLine> lines) throws JSONException {
        JSONArray arr = new JSONArray();
        if (lines != null) {
            for (TerminalLine l : lines) arr.put(line(l));
        }
        return arr;
    }

    static JSONObject cursor(TerminalCursor c) throws JSONException {
        JSONObject o = new JSONObject();
        if (c == null) return o;
        o.put("row", c.row);
        o.put("col", c.col);
        o.put("visible", c.visible);
        o.put("shape", String.valueOf(c.shape));
        o.put("blink", c.blink);
        return o;
    }

    static JSONObject color(TerminalColor c) throws JSONException {
        JSONObject o = new JSONObject();
        if (c == null) return o;
        o.put("kind", String.valueOf(c.kind));
        o.put("index", c.index);
        o.put("rgb", c.rgb);
        return o;
    }

    /** 历史窗口捕获行数硬上限，超出仅保留最近 N 行并置 historyTruncated。 */
    private static final int HISTORY_CAPTURE_MAX_LINES = 300;

    /**
     * android/render-snapshot.json / current-model-state.json：不可变 RenderSnapshot 的稳定 JSON。
     * includeHistory=true 时附带最近 HISTORY_CAPTURE_MAX_LINES 行历史窗口（含截断字段），
     * 用于排查历史滚动/prepend/append/锚点错误；render-updates 序列为控制体积可传 false。
     */
    static JSONObject renderSnapshot(RemoteTerminalModel.RenderSnapshot s, boolean includeHistory)
            throws JSONException {
        JSONObject o = new JSONObject();
        if (s == null) {
            o.put("available", false);
            return o;
        }
        o.put("available", true);
        o.put("instanceId", s.instanceId);
        o.put("layoutEpoch", s.layoutEpoch);
        o.put("screenRevision", s.screenRevision);
        o.put("rows", s.rows);
        o.put("columns", s.columns);
        o.put("activeBuffer", String.valueOf(s.activeBuffer));
        o.put("cursor", cursor(s.cursor));
        o.put("screen", lines(s.screen));
        o.put("title", s.title);
        o.put("workingDirectory", s.workingDirectory);
        o.put("firstAvailableHistorySeq", s.firstAvailableHistorySeq);
        o.put("hasMoreHistoryBefore", s.hasMoreHistoryBefore);
        o.put("history", historyWindow(s, includeHistory));
        return o;
    }

    /** 序列化有界历史窗口，附 totalSize/fromSeq/toSeq/truncated 供离线核对。 */
    private static JSONObject historyWindow(RemoteTerminalModel.RenderSnapshot s, boolean include)
            throws JSONException {
        JSONObject h = new JSONObject();
        com.webterm.terminal.model.TerminalHistoryView hist = s.history;
        int total = hist != null ? hist.size() : 0;
        h.put("historyTotalSize", total);
        if (!include || hist == null || total == 0) {
            h.put("historyCapturedLines", 0);
            h.put("historyTruncated", total > 0);
            return h;
        }
        int from = Math.max(0, total - HISTORY_CAPTURE_MAX_LINES);
        boolean truncated = from > 0;
        JSONArray arr = new JSONArray();
        long fromSeq = -1, toSeq = -1;
        for (int i = from; i < total; i++) {
            com.webterm.terminal.model.TerminalLine line = hist.lineAt(i);
            if (line == null) continue;
            arr.put(line(line));
            if (fromSeq < 0) fromSeq = line.historyOrder();
            toSeq = line.historyOrder();
        }
        h.put("historyCapturedFromSeq", fromSeq);
        h.put("historyCapturedToSeq", toSeq);
        h.put("historyCapturedLines", arr.length());
        h.put("historyTruncated", truncated);
        h.put("lines", arr);
        return h;
    }

    /** android/mapped-frames.jsonl 中的 snapshot 条目。 */
    static JSONObject mappedSnapshot(ScreenBaseline s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", "baseline");
        o.put("sessionId", s.sessionId);
        o.put("instanceId", s.instanceId);
        o.put("layoutEpoch", s.layoutEpoch);
        o.put("screenRevision", s.screenRevision);
        o.put("streamGeneration", s.streamGeneration);
        o.put("rows", s.rows);
        o.put("cols", s.cols);
        o.put("activeBuffer", String.valueOf(s.activeBuffer));
        o.put("cursor", cursor(s.cursor));
        o.put("screen", lineList(s.screen));
        o.put("historyExtentFirst", s.historyExtent.firstSeq);
        o.put("historyExtentLast", s.historyExtent.lastSeq);
        o.put("historyTail", lineList(s.historyTail));
        o.put("title", s.title);
        o.put("workingDirectory", s.workingDirectory);
        return o;
    }

    /** android/mapped-frames.jsonl 中的 patch 条目。 */
    static JSONObject mappedPatch(ScreenPatchV2 p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", "patch");
        o.put("instanceId", p.instanceId);
        o.put("layoutEpoch", p.layoutEpoch);
        o.put("baseRevision", p.baseRevision);
        o.put("screenRevision", p.screenRevision);
        o.put("streamGeneration", p.streamGeneration);
        JSONArray layout = new JSONArray();
        if (p.layout != null) for (long id : p.layout) layout.put(id);
        o.put("layout", layout);
        o.put("lineUpdates", lineList(p.lineUpdates));
        o.put("cursor", cursor(p.cursor));
        o.put("activeBuffer", String.valueOf(p.activeBuffer));
        o.put("title", p.title);
        o.put("workingDirectory", p.workingDirectory);
        return o;
    }

    static JSONObject modelState(CapturedModelState m) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("capturedAtMillis", m.capturedAtMillis);
        o.put("instanceId", m.instanceId);
        o.put("layoutEpoch", m.layoutEpoch);
        o.put("screenRevision", m.screenRevision);
        o.put("remoteScreenRevision", m.remoteScreenRevision);
        o.put("displayHistoryExtent", historyExtent(m.displayHistoryExtent));
        o.put("remoteHistoryExtent", historyExtent(m.remoteHistoryExtent));
        o.put("rows", m.rows);
        o.put("columns", m.columns);
        o.put("activeBuffer", m.activeBuffer);
        o.put("projectionComplete", m.projectionComplete);
        o.put("afterBaseline", m.afterBaseline);
        return o;
    }

    private static JSONObject historyExtent(HistoryExtent extent) throws JSONException {
        JSONObject o = new JSONObject();
        HistoryExtent value = extent == null ? HistoryExtent.INITIAL_EMPTY : extent;
        o.put("firstSeq", value.firstSeq);
        o.put("lastSeq", value.lastSeq);
        o.put("empty", value.isEmpty());
        return o;
    }

    static JSONObject viewState(CapturedViewState v) throws JSONException {
        JSONObject o = new JSONObject();
        if (v == null) {
            o.put("available", false);
            return o;
        }
        o.put("available", true);
        o.put("capturedAtMillis", v.capturedAtMillis);
        o.put("viewWidth", v.viewWidth);
        o.put("viewHeight", v.viewHeight);
        o.put("paddingLeft", v.paddingLeft);
        o.put("paddingTop", v.paddingTop);
        o.put("paddingRight", v.paddingRight);
        o.put("paddingBottom", v.paddingBottom);
        o.put("fontSizeSp", v.fontSizeSp);
        o.put("typeface", v.typefaceDescription);
        o.put("cellWidth", v.cellWidth);
        o.put("lineHeight", v.lineHeight);
        o.put("baseline", v.baseline);
        o.put("scrollOffsetPixels", v.scrollOffsetPixels);
        o.put("followTail", v.followTail);
        o.put("contentStreamIntent", v.contentStreamIntent);
        o.put("liveScreenExitOffsetPixels", v.liveScreenExitOffsetPixels);
        o.put("pureHistory", v.pureHistory);
        o.put("keyboardVisible", v.keyboardVisible);
        o.put("renderedScreenRevision", v.renderedScreenRevision);
        o.put("renderedLayoutEpoch", v.renderedLayoutEpoch);
        o.put("renderedInstanceId", v.renderedInstanceId);
        o.put("cursorBlinkOn", v.cursorBlinkOn);
        o.put("hasSelection", v.hasSelection);
        return o;
    }

    static JSONObject dirty(RenderDirtyState d) throws JSONException {
        JSONObject o = new JSONObject();
        if (d == null) return o;
        o.put("fullInvalidate", d.fullInvalidate);
        o.put("changedScreenRows", d.changedScreenRows != null ? d.changedScreenRows.cardinality() : 0);
        o.put("historyChanged", d.historyChanged);
        o.put("geometryChanged", d.geometryChanged);
        o.put("cursorChanged", d.cursorChanged);
        o.put("paletteChanged", d.paletteChanged);
        o.put("stylesChanged", d.stylesChanged);
        o.put("linksChanged", d.linksChanged);
        o.put("modesChanged", d.modesChanged);
        o.put("activeBufferChanged", d.activeBufferChanged);
        return o;
    }

    static JSONObject terminalState(TerminalStateUpdate s) throws JSONException {
        JSONObject o = new JSONObject();
        if (s == null) return o;
        o.put("geometryChanged", s.geometryChanged);
        o.put("historyChanged", s.historyChanged);
        o.put("titleChanged", s.titleChanged);
        o.put("workingDirectoryChanged", s.workingDirectoryChanged);
        o.put("tailAppendedLines", s.tailAppendedLines);
        o.put("historyPrependedLines", s.historyPrependedLines);
        return o;
    }
}
