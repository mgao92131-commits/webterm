package diagnostics

import (
	"strings"
	"testing"
	"time"

	"webterm/go-core/internal/logs"
)

// TestBuildSummaryCountsEventNames 验证基于事件名的统计真正工作：
// mux/relay 事件改走 Logger.Event 后，summary.txt 必须能数出对应次数。
func TestBuildSummaryCountsEventNames(t *testing.T) {
	events := []logs.Entry{
		{Seq: 1, Time: time.Now(), Event: "relay_connected"},
		{Seq: 2, Time: time.Now(), Event: "relay_disconnected"},
		{Seq: 3, Time: time.Now(), Event: "mux_writer_failed"},
		{Seq: 4, Time: time.Now(), Event: "mux_writer_failed"},
		{Seq: 5, Time: time.Now(), Event: "mux_channel_open"},
		{Seq: 6, Time: time.Now(), Message: "free text without event"},
	}
	summary := BuildSummary(exportTestManifest(), events, nil, nil)
	for _, want := range []string{
		"Relay connects/disconnects: 1/1",
		"Mux writer failures: 2",
	} {
		if !strings.Contains(summary, want) {
			t.Errorf("summary missing %q\nsummary:\n%s", want, summary)
		}
	}
}
