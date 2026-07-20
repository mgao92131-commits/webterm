package session

import (
	"context"
	"time"
)

// flushScreenPending 等待 mailbox 中尚未写出的屏幕状态被 writeLoop 取走。
// 只保证"取走"而非"写完"已足够：writeLoop 是单 goroutine，正在进行的
// writeMessage 一定会先于之后入队的 Exit 完成，因此返回后入队的 Exit 不会
// 超过任何真实屏幕帧。mailbox 取走后残留的 screenWake 信号只会触发一次
// 空读（hasScreenData=false），不会写出多余帧。
//
// 仅在进程退出后的排空路径（waitLoop）使用；ctx 超时或 client 关闭时放弃等待。
func (client *terminalChannelRuntime) flushScreenPending(ctx context.Context) {
	for {
		client.screenMu.Lock()
		pending := client.hasScreenData
		client.screenMu.Unlock()
		if !pending {
			return
		}
		select {
		case client.screenWake <- struct{}{}:
		default:
		}
		select {
		case <-client.done:
			return
		case <-ctx.Done():
			return
		case <-time.After(5 * time.Millisecond):
		}
	}
}
