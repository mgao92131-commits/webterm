package com.webterm.mobile.diagnostics;

import com.webterm.terminal.model.Hyperlink;
import com.webterm.terminal.model.RenderDirtyState;
import com.webterm.terminal.model.RemoteTerminalModel;
import com.webterm.terminal.model.ScreenPatch;
import com.webterm.terminal.model.ScreenSnapshot;
import com.webterm.terminal.model.TerminalCell;
import com.webterm.terminal.model.TerminalColor;
import com.webterm.terminal.model.TerminalCursor;
import com.webterm.terminal.model.TerminalLine;
import com.webterm.terminal.model.TerminalStateUpdate;
import com.webterm.terminal.model.TerminalStyle;
import com.webterm.terminal.model.capture.CapturedModelState;
import com.webterm.terminal.model.capture.CapturedViewState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

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
        o.put("styleId", c.styleId);
        o.put("linkId", c.linkId);
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

    static JSONArray styles(Map<Integer, TerminalStyle> styles) throws JSONException {
        JSONArray arr = new JSONArray();
        if (styles != null) {
            for (TerminalStyle s : styles.values()) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("fg", color(s.fg));
                o.put("bg", color(s.bg));
                o.put("ulColor", color(s.underlineColor));
                o.put("attrs", s.attrs);
                arr.put(o);
            }
        }
        return arr;
    }

    static JSONArray links(Map<Integer, Hyperlink> links) throws JSONException {
        JSONArray arr = new JSONArray();
        if (links != null) {
            for (Hyperlink l : links.values()) {
                JSONObject o = new JSONObject();
                o.put("id", l.id);
                o.put("uri", l.uri);
                arr.put(o);
            }
        }
        return arr;
    }

    /** android/render-snapshot.json：当前用于绘制的不可变 RenderSnapshot。 */
    static JSONObject renderSnapshot(RemoteTerminalModel.RenderSnapshot s) throws JSONException {
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
        o.put("styles", styles(s.styles));
        o.put("links", links(s.links));
        o.put("title", s.title);
        o.put("workingDirectory", s.workingDirectory);
        o.put("firstAvailableHistorySeq", s.firstAvailableHistorySeq);
        o.put("hasMoreHistoryBefore", s.hasMoreHistoryBefore);
        o.put("historySize", s.history != null ? s.history.size() : 0);
        return o;
    }

    /** android/mapped-frames.jsonl 中的 snapshot 条目。 */
    static JSONObject mappedSnapshot(ScreenSnapshot s) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", "snapshot");
        o.put("sessionId", s.sessionId);
        o.put("instanceId", s.instanceId);
        o.put("layoutEpoch", s.layoutEpoch);
        o.put("screenRevision", s.screenRevision);
        o.put("rows", s.rows);
        o.put("cols", s.cols);
        o.put("activeBuffer", String.valueOf(s.activeBuffer));
        o.put("cursor", cursor(s.cursor));
        o.put("screen", lineList(s.screen));
        o.put("styles", styles(s.styles));
        o.put("links", links(s.links));
        o.put("title", s.title);
        o.put("workingDirectory", s.workingDirectory);
        return o;
    }

    /** android/mapped-frames.jsonl 中的 patch 条目。 */
    static JSONObject mappedPatch(ScreenPatch p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("kind", "patch");
        o.put("instanceId", p.instanceId);
        o.put("layoutEpoch", p.layoutEpoch);
        o.put("baseRevision", p.baseRevision);
        o.put("screenRevision", p.screenRevision);
        JSONArray layout = new JSONArray();
        if (p.layout != null) for (long id : p.layout) layout.put(id);
        o.put("layout", layout);
        o.put("lineUpdates", lineList(p.lineUpdates));
        JSONArray appendSeqs = new JSONArray();
        if (p.historyAppendSeqs != null) for (Long seq : p.historyAppendSeqs) appendSeqs.put(seq);
        o.put("historyAppendSeqs", appendSeqs);
        o.put("cursor", cursor(p.cursor));
        o.put("newStyles", styles(p.newStyles));
        o.put("newLinks", links(p.newLinks));
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
        o.put("rows", m.rows);
        o.put("columns", m.columns);
        o.put("activeBuffer", m.activeBuffer);
        o.put("projectionComplete", m.projectionComplete);
        o.put("afterSnapshot", m.afterSnapshot);
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
