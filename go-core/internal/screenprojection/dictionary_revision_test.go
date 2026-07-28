package screenprojection

import (
	"testing"

	"webterm/go-core/internal/terminalengine"
)

// 历史中首次出现的 style 必须在当次导出的 revision 记入 created index；
// 否则 resume 会误判客户端字典缺失并重复发送，或更糟地漏发。
func TestChangeIndex_HistoryStyleCreatedAtExportRevision(t *testing.T) {
	engine, _, p := newHistoryRig(t, 5, 20)
	fillScreenStable(t, engine, 5)
	frame1 := p.ExportState(0, 1)
	if len(frame1.Styles) != 0 {
		t.Fatalf("stable screen export styles=%d, want 0", len(frame1.Styles))
	}

	// 滚动区域内写入 styled 行并再滚一行 plain，使 styled 只留在历史缓存路径。
	mustEngineWrite(t, engine, "\x1b[1;2r\x1b[2;1H\x1b[31mred history\x1b[0m\r\n\x1b[2;1Hplain\r\n\x1b[r")
	frame2 := p.ExportState(0, 2)
	if len(frame2.Styles) == 0 {
		t.Fatal("styled history export must include dictionary entries")
	}
	if len(p.changeIndex.StyleCreatedRevision) != len(frame2.Styles) {
		t.Fatalf("style created index len=%d, want %d (created=%v)",
			len(p.changeIndex.StyleCreatedRevision), len(frame2.Styles), p.changeIndex.StyleCreatedRevision)
	}
	for i, created := range p.changeIndex.StyleCreatedRevision {
		if created != frame2.Seq {
			t.Fatalf("style %d created revision=%d, want export seq %d",
				i, created, frame2.Seq)
		}
	}

	mustEngineWrite(t, engine, "\x1b[1;2r\x1b[2;1Hmore\r\n\x1b[r")
	frame3 := p.ExportState(0, 3)
	if len(frame3.Styles) != len(frame2.Styles) {
		t.Fatalf("style count changed %d -> %d without new styles",
			len(frame2.Styles), len(frame3.Styles))
	}
	for i, created := range p.changeIndex.StyleCreatedRevision {
		if created != frame2.Seq {
			t.Fatalf("style %d created revision=%d after follow-up export, want %d",
				i, created, frame2.Seq)
		}
	}
}

// 客户端在 styled 历史已入字典的 revision 上 resume，不应重复发送 Styles。
func TestResumeCommit_DoesNotRepeatKnownHistoryStyles(t *testing.T) {
	engine, _, p := newHistoryRig(t, 5, 20)
	fillScreenStable(t, engine, 5)
	mustEngineWrite(t, engine, "\x1b[1;2r\x1b[2;1H\x1b[31mred history\x1b[0m\r\n\x1b[2;1Hplain\r\n\x1b[r")
	old := p.ExportState(0, 2)
	if len(old.Styles) == 0 {
		t.Fatal("test requires styled history export")
	}
	token := resumeTokenFor(old)

	mustEngineWrite(t, engine, "\x1b[1;2r\x1b[2;1Htail\r\n\x1b[r")
	result := p.Resume(token, 0, 5, false)
	if result.Kind != ResumeCommit {
		t.Fatalf("kind=%v, want ResumeCommit", result.Kind)
	}
	if len(result.Frame.Styles) > 0 {
		t.Fatalf("resume repeated %d styles already known at revision %d",
			len(result.Frame.Styles), token.ScreenRevision)
	}
}

// OSC8 link 的 created revision 必须与首次导出的 revision 对齐。
func TestChangeIndex_LinkCreatedRevisionMatchesExportSeq(t *testing.T) {
	engine, _, p := newChangeIndexFixture(3, 20)
	mustEngineWrite(t, engine, "\x1b]8;;https://example.com\x07link\x1b]8;;\x07")
	frame := p.ExportState(0, 1)
	if len(frame.Links) == 0 {
		t.Fatal("linked export must include dictionary entries")
	}
	if len(p.changeIndex.LinkCreatedRevision) != len(frame.Links) {
		t.Fatalf("link created index len=%d, want %d",
			len(p.changeIndex.LinkCreatedRevision), len(frame.Links))
	}
	for i, created := range p.changeIndex.LinkCreatedRevision {
		if created != frame.Seq {
			t.Fatalf("link %d created revision=%d, want export seq %d",
				i, created, frame.Seq)
		}
	}
}

func TestResumeCommit_DoesNotRepeatKnownLinks(t *testing.T) {
	engine, _, p := newChangeIndexFixture(3, 20)
	mustEngineWrite(t, engine, "hi")
	p.ExportState(0, 1)
	mustEngineWrite(t, engine, "\x1b]8;;https://example.com\x07link\x1b]8;;\x07")
	old := p.ExportState(0, 2)
	if len(old.Links) == 0 {
		t.Fatal("test requires linked export")
	}
	token := resumeTokenFor(old)

	mustEngineWrite(t, engine, "!")
	result := p.Resume(token, 0, 3, false)
	if result.Kind != ResumeCommit {
		t.Fatalf("kind=%v, want ResumeCommit", result.Kind)
	}
	if len(result.Frame.Links) > 0 {
		t.Fatalf("resume repeated %d links already known at revision %d",
			len(result.Frame.Links), token.ScreenRevision)
	}
}

func TestResume_ForceBaselineSkipsCommit(t *testing.T) {
	sb := terminalengine.NewTrackedScrollback(100, nil)
	engine := terminalengine.NewEngine(3, 12, sb)
	if err := engine.Write([]byte("a")); err != nil {
		t.Fatal(err)
	}
	projector := NewProjector(engine, sb, "session", "instance")
	old := projector.ExportState(1, 3)
	token := resumeTokenFor(old)
	if err := engine.Write([]byte("b")); err != nil {
		t.Fatal(err)
	}
	projector.ExportState(1, 4)

	result := projector.Resume(token, 1, 9, true)
	if result.Kind != ResumeBaseline {
		t.Fatalf("kind=%v, want ResumeBaseline", result.Kind)
	}
	if result.State.Kind != terminalengine.FrameSnapshot {
		t.Fatalf("baseline kind=%v, want snapshot", result.State.Kind)
	}
}
