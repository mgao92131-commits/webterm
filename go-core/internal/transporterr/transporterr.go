// Package transporterr 存放传输层共享的 sentinel 错误。
//
// 这些小 sentinel 需要被多个传输相关包引用（例如 mux 分类错误、relay
// 产生错误），放在一个零依赖的小包里可避免包之间产生循环依赖。
package transporterr

import "errors"

// ErrRelayStreamClosed 表示一条 relay 逻辑 stream 已被正常关闭。
// 它是预期的连接清理信号，不应被归类为未知 I/O 故障。
var ErrRelayStreamClosed = errors.New("relay stream closed")
