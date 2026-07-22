// render-capture-inspect 读取一个 webterm-render-capture-<id>.zip 现场包，
// 输出跨阶段一致性报告：manifest 摘要、校验和核对、Agent/Android wire 哈希对比、
// canonical/mapped/model/render revision 对比、逐行文本与宽度差异、layout line ID 差异、
// style/link 引用缺失，以及“最可能出错的阶段”启发式判断。
//
// 用法： render-capture-inspect <capture.zip>
//
// 仅依赖标准库；现场包 schema 见 docs/render-capture/INVESTIGATION.md 与
// go-core/internal/terminalcapture（Agent 端）及 app/src/diagnostics（Android 端）。
package main

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: render-capture-inspect <capture.zip>")
		os.Exit(2)
	}
	files, err := readZip(os.Args[1])
	if err != nil {
		fmt.Fprintf(os.Stderr, "open zip: %v\n", err)
		os.Exit(1)
	}
	r := &report{files: files}
	r.manifestSummary()
	r.verifyChecksums()
	r.revisionComparison()
	r.wireHashComparison()
	r.lineDiff()
	r.styleLinkIntegrity()
	r.likelyStage()
	fmt.Print(r.String())
}

// ---- ZIP 读取 ----

func readZip(path string) (map[string][]byte, error) {
	zr, err := zip.OpenReader(path)
	if err != nil {
		return nil, err
	}
	defer zr.Close()
	out := map[string][]byte{}
	for _, f := range zr.File {
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		data, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			return nil, err
		}
		out[f.Name] = data
	}
	return out, nil
}

// ---- 报告 ----

type report struct {
	files   map[string][]byte
	sb      strings.Builder
	issues  []string
	stage   []string
}

func (r *report) String() string { return r.sb.String() }

func (r *report) section(title string) {
	fmt.Fprintf(&r.sb, "\n=== %s ===\n", title)
}

func (r *report) jsonOf(path string) (map[string]any, bool) {
	data, ok := r.files[path]
	if !ok {
		return nil, false
	}
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, false
	}
	return m, true
}

func (r *report) arrayOf(path string) ([]any, bool) {
	data, ok := r.files[path]
	if !ok {
		return nil, false
	}
	var a []any
	if err := json.Unmarshal(data, &a); err != nil {
		return nil, false
	}
	return a, true
}

// ---- 1. manifest 摘要 ----

func (r *report) manifestSummary() {
	r.section("manifest summary")
	m, ok := r.jsonOf("manifest.json")
	if !ok {
		r.sb.WriteString("manifest.json 缺失或非法\n")
		r.issues = append(r.issues, "manifest missing")
		return
	}
	for _, k := range []string{"schemaVersion", "captureId", "createdAt", "terminalInstanceId",
		"clientInstanceId", "sessionId", "layoutEpoch",
		"androidModelRevision", "androidRenderedRevision", "agentRevision",
		"androidAppVersion", "agentVersion", "agentPlatform", "agentBuildMode",
		"rows", "cols", "viewWidth", "viewHeight", "cellWidth", "lineHeight", "fontSize",
		"keyboardVisible", "screenshotAvailable", "agentAvailable"} {
		if v, ok := m[k]; ok {
			fmt.Fprintf(&r.sb, "  %-26s = %v\n", k, v)
		}
	}
	if trunc, ok := m["truncated"].(map[string]any); ok {
		fmt.Fprintf(&r.sb, "  truncated                  = %v\n", trunc)
		for k, v := range trunc {
			if b, ok := v.(bool); ok && b {
				r.issues = append(r.issues, "truncated:"+k)
			}
		}
	}
}

// ---- 2. 校验和核对 ----

func (r *report) verifyChecksums() {
	r.section("checksums.sha256 verification")
	data, ok := r.files["checksums.sha256"]
	if !ok {
		r.sb.WriteString("checksums.sha256 缺失\n")
		r.issues = append(r.issues, "checksums missing")
		return
	}
	bad := 0
	checked := 0
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		parts := strings.SplitN(line, "  ", 2)
		if len(parts) != 2 {
			continue
		}
		want, path := parts[0], parts[1]
		entry, ok := r.files[path]
		if !ok {
			fmt.Fprintf(&r.sb, "  MISSING  %s\n", path)
			bad++
			continue
		}
		sum := sha256.Sum256(entry)
		if hex.EncodeToString(sum[:]) != want {
			fmt.Fprintf(&r.sb, "  BADHASH  %s\n", path)
			bad++
		}
		checked++
	}
	fmt.Fprintf(&r.sb, "  检查 %d 个文件，%d 个不一致\n", checked, bad)
	if bad > 0 {
		r.issues = append(r.issues, "checksum mismatch")
	}
}

// ---- 3. revision 对比 ----

func (r *report) revisionComparison() {
	r.section("revision comparison (canonical / model / render / agent)")
	agentRev, _ := r.manifestNum("agentRevision")
	modelRev, _ := r.manifestNum("androidModelRevision")
	renderedRev, _ := r.manifestNum("androidRenderedRevision")

	canonicalRev := numFromFrame(r.canonicalFrame())
	modelStateRev := r.modelStateMaxRevision()
	renderSnapRev := numFromFrame(r.renderSnapshot())

	fmt.Fprintf(&r.sb, "  agentRevision (manifest)        = %s\n", fmtNum(agentRev))
	fmt.Fprintf(&r.sb, "  canonical frame screenRevision  = %s\n", fmtNum(canonicalRev))
	fmt.Fprintf(&r.sb, "  android model-state revision    = %s\n", fmtNum(modelStateRev))
	fmt.Fprintf(&r.sb, "  android render-snapshot revision= %s\n", fmtNum(renderSnapRev))
	fmt.Fprintf(&r.sb, "  manifest androidModelRevision   = %s\n", fmtNum(modelRev))
	fmt.Fprintf(&r.sb, "  manifest androidRenderedRevision= %s\n", fmtNum(renderedRev))

	// 三者不要求相等；不一致本身是诊断信息，但给出方向性提示。
	if canonicalRev != nil && renderSnapRev != nil && *canonicalRev < *renderSnapRev {
		r.note("canonical revision < android rendered revision：Agent 权威帧可能滞后于客户端（合帧/丢帧）")
	}
}

func (r *report) manifestNum(key string) (*float64, bool) {
	m, ok := r.jsonOf("manifest.json")
	if !ok {
		return nil, false
	}
	if v, ok := m[key].(float64); ok {
		return &v, true
	}
	return nil, false
}

func (r *report) modelStateMaxRevision() *float64 {
	arr, ok := r.arrayOf("android/model-state.json")
	if !ok || len(arr) == 0 {
		return nil
	}
	var max *float64
	for _, item := range arr {
		if m, ok := item.(map[string]any); ok {
			if v, ok := m["screenRevision"].(float64); ok {
				if max == nil || v > *max {
					vv := v
					max = &vv
				}
			}
		}
	}
	return max
}

// ---- 4. wire 哈希对比 ----

func (r *report) wireHashComparison() {
	r.section("wire hash comparison (agent vs android)")
	agentIdx, aOK := r.arrayOf("agent/wire/index.json")
	androidIdx, dOK := r.arrayOf("android/wire/index.json")
	if !aOK || !dOK {
		r.sb.WriteString("  agent 或 android wire 索引缺失，跳过\n")
		return
	}
	// 按 (kind, screenRevision) 建索引便于配对。
	type key struct {
		kind string
		rev  float64
	}
	agentBy := map[key]string{}
	for _, e := range agentIdx {
		m, _ := e.(map[string]any)
		k := key{str(m["kind"]), num(m["screenRevision"])}
		agentBy[k] = str(m["sha256"])
	}
	matched, mismatched, androidOnly := 0, 0, 0
	for _, e := range androidIdx {
		m, _ := e.(map[string]any)
		k := key{str(m["messageKind"]), num(m["screenRevision"])}
		// android 索引 messageKind 为大写枚举（SNAPSHOT/PATCH），归一化为小写。
		k.kind = strings.ToLower(k.kind)
		if ah, ok := agentBy[k]; ok {
			if ah == str(m["sha256"]) {
				matched++
			} else {
				mismatched++
				fmt.Fprintf(&r.sb, "  HASH-DIFF kind=%s rev=%v\n", k.kind, k.rev)
			}
		} else {
			androidOnly++
		}
	}
	// 最稳健的完整性信号：sha256 多重集重叠。Android 原始 wire 在 parse 前记录，
	// 不携带 screenRevision，因此按 (kind,rev) 配对可能落空；若某帧字节两端完全一致，
	// 其 sha256 必然相同，重叠数即“完整传输”的帧数。
	agentSha := map[string]int{}
	for _, e := range agentIdx {
		m, _ := e.(map[string]any)
		agentSha[str(m["sha256"])]++
	}
	intact := 0
	for _, e := range androidIdx {
		m, _ := e.(map[string]any)
		h := str(m["sha256"])
		if agentSha[h] > 0 {
			agentSha[h]--
			intact++
		}
	}
	fmt.Fprintf(&r.sb, "  agent wire 帧=%d, android wire 帧=%d\n", len(agentIdx), len(androidIdx))
	fmt.Fprintf(&r.sb, "  按(kind,rev)配对一致=%d, 哈希不一致=%d, android 独有=%d\n", matched, mismatched, androidOnly)
	fmt.Fprintf(&r.sb, "  字节完全一致（sha256 重叠）=%d\n", intact)
	if mismatched > 0 {
		r.note("wire 哈希不一致：Agent 编码字节与 Android 接收字节不同 → 传输/编码阶段问题")
		r.stage = append(r.stage, "wire/transport")
	}
}

// ---- 5. 逐行文本/宽度 + layout line ID 差异 ----

func (r *report) lineDiff() {
	r.section("line text/width + layout line-id diff (agent canonical vs android render)")
	agentFrame := r.canonicalFrame()
	androidFrame := r.renderSnapshot()
	if agentFrame == nil || androidFrame == nil {
		r.sb.WriteString("  canonical 或 render-snapshot 缺失，跳过\n")
		return
	}
	agentLines := flattenScreen(agentFrame)
	androidLines := flattenScreen(androidFrame)

	agentLayout := layoutIDs(agentFrame)
	androidLayout := layoutIDs(androidFrame)
	r.diffLayout(agentLayout, androidLayout)

	// 按 lineId 对齐比较文本与宽度。
	agentByID := map[float64]rowText{}
	for id, rt := range agentLines {
		agentByID[id] = rt
	}
	textDiff, widthDiff := 0, 0
	compared := 0
	for id, aRT := range androidLines {
		gRT, ok := agentByID[id]
		if !ok {
			continue
		}
		compared++
		if gRT.text != aRT.text {
			textDiff++
			if textDiff <= 5 {
				fmt.Fprintf(&r.sb, "  TEXT-DIFF lineId=%v agent=%q android=%q\n", id, truncStr(gRT.text), truncStr(aRT.text))
			}
		}
		if gRT.width != aRT.width {
			widthDiff++
			if widthDiff <= 5 {
				fmt.Fprintf(&r.sb, "  WIDTH-DIFF lineId=%v agent=%d android=%d\n", id, gRT.width, aRT.width)
			}
		}
	}
	fmt.Fprintf(&r.sb, "  比对 %d 行（按 lineId 对齐），文本差异=%d，宽度差异=%d\n", compared, textDiff, widthDiff)
	if textDiff > 0 || widthDiff > 0 {
		r.note("行文本/宽度在权威帧与渲染快照间不一致 → mapper/model/render 阶段问题")
		r.stage = append(r.stage, "mapper/model/render")
	}
}

func (r *report) diffLayout(agent, android []float64) {
	if len(agent) == 0 || len(android) == 0 {
		return
	}
	n := len(agent)
	if len(android) < n {
		n = len(android)
	}
	diff := 0
	for i := 0; i < n; i++ {
		if agent[i] != android[i] {
			diff++
		}
	}
	if len(agent) != len(android) {
		fmt.Fprintf(&r.sb, "  LAYOUT-LEN agent=%d android=%d\n", len(agent), len(android))
	}
	fmt.Fprintf(&r.sb, "  layout line-id 差异行数=%d\n", diff)
	if diff > 0 || len(agent) != len(android) {
		r.note("layout line-id 序列不一致 → 滚动/布局阶段问题")
		r.stage = append(r.stage, "layout/scroll")
	}
}

// ---- 6. style/link 引用完整性 ----

func (r *report) styleLinkIntegrity() {
	r.section("style/link reference integrity (agent canonical)")
	frame := r.canonicalFrame()
	if frame == nil {
		r.sb.WriteString("  canonical 缺失，跳过\n")
		return
	}
	styles := idSet(frame, "styles")
	links := idSet(frame, "links")
	missingStyle, missingLink := 0, 0
	for _, line := range screenLines(frame) {
		for _, cell := range cellsOf(line) {
			sid := num(cell["styleId"])
			lid := num(cell["linkId"])
			if sid != 0 && !styles[sid] {
				missingStyle++
			}
			if lid != 0 && !links[lid] {
				missingLink++
			}
		}
	}
	fmt.Fprintf(&r.sb, "  缺失 style 引用=%d，缺失 link 引用=%d\n", missingStyle, missingLink)
	if missingStyle > 0 || missingLink > 0 {
		r.note("canonical 帧存在悬空 style/link 引用 → 字典轮转/导出阶段问题")
		r.stage = append(r.stage, "dictionary/export")
	}
}

// ---- 7. 最可能出错阶段（启发式）----

func (r *report) likelyStage() {
	r.section("likely faulty stage (heuristic)")
	if len(r.stage) == 0 && len(r.issues) == 0 {
		r.sb.WriteString("  未发现明显跨阶段不一致（仅启发式，不能替代人工核对）。\n")
		return
	}
	if len(r.stage) > 0 {
		uniq := unique(r.stage)
		fmt.Fprintf(&r.sb, "  候选阶段：%s\n", strings.Join(uniq, ", "))
	}
	if len(r.issues) > 0 {
		fmt.Fprintf(&r.sb, "  其它信号：%s\n", strings.Join(unique(r.issues), ", "))
	}
}

func (r *report) note(s string) {
	r.issues = append(r.issues, s)
}

// ---- 帧解析辅助（兼容 Agent runs 与 Android flat cells 两种行格式）----

func (r *report) canonicalFrame() map[string]any {
	m, ok := r.jsonOf("agent/canonical-state.json")
	if !ok {
		return nil
	}
	if f, ok := m["frame"].(map[string]any); ok {
		return f
	}
	return m
}

func (r *report) renderSnapshot() map[string]any {
	m, ok := r.jsonOf("android/render-snapshot.json")
	if !ok {
		return nil
	}
	if avail, ok := m["available"].(bool); ok && !avail {
		return nil
	}
	return m
}

func numFromFrame(frame map[string]any) *float64 {
	if frame == nil {
		return nil
	}
	if v, ok := frame["screenRevision"].(float64); ok {
		return &v
	}
	return nil
}

func screenLines(frame map[string]any) []map[string]any {
	arr, ok := frame["screen"].([]any)
	if !ok {
		return nil
	}
	out := make([]map[string]any, 0, len(arr))
	for _, e := range arr {
		if m, ok := e.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

// cellsOf 兼容 Android flat "cells" 与 Agent "runs"（含 col + cells）。
func cellsOf(line map[string]any) []map[string]any {
	if arr, ok := line["cells"].([]any); ok {
		return mapsOf(arr)
	}
	var out []map[string]any
	if runs, ok := line["runs"].([]any); ok {
		for _, rn := range runs {
			if rm, ok := rn.(map[string]any); ok {
				if cs, ok := rm["cells"].([]any); ok {
					out = append(out, mapsOf(cs)...)
				}
			}
		}
	}
	return out
}

type rowText struct {
	text  string
	width int
}

// flattenScreen 按 lineId 汇总每行的文本与总宽度。
func flattenScreen(frame map[string]any) map[float64]rowText {
	out := map[float64]rowText{}
	for _, line := range screenLines(frame) {
		id := num(line["lineId"])
		var sb strings.Builder
		width := 0
		for _, cell := range cellsOf(line) {
			sb.WriteString(str(cell["text"]))
			width += int(num(cell["width"]))
		}
		out[id] = rowText{text: sb.String(), width: width}
	}
	return out
}

func layoutIDs(frame map[string]any) []float64 {
	arr, ok := frame["layout"].([]any)
	if !ok {
		// 无显式 layout 时由 screen lineId 派生。
		var ids []float64
		for _, line := range screenLines(frame) {
			ids = append(ids, num(line["lineId"]))
		}
		return ids
	}
	ids := make([]float64, 0, len(arr))
	for _, e := range arr {
		ids = append(ids, num(e))
	}
	return ids
}

func idSet(frame map[string]any, key string) map[float64]bool {
	set := map[float64]bool{}
	if arr, ok := frame[key].([]any); ok {
		for _, e := range arr {
			if m, ok := e.(map[string]any); ok {
				set[num(m["id"])] = true
			}
		}
	}
	return set
}

// ---- 通用小工具 ----

func mapsOf(arr []any) []map[string]any {
	out := make([]map[string]any, 0, len(arr))
	for _, e := range arr {
		if m, ok := e.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func str(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func num(v any) float64 {
	if f, ok := v.(float64); ok {
		return f
	}
	return 0
}

func fmtNum(v *float64) string {
	if v == nil {
		return "<none>"
	}
	return fmt.Sprintf("%.0f", *v)
}

func truncStr(s string) string {
	r := []rune(s)
	if len(r) > 40 {
		return string(r[:40]) + "…"
	}
	return s
}

func unique(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, s := range in {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	sort.Strings(out)
	return out
}
