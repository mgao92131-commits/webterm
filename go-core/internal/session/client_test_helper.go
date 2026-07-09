package session

// SetReadyForTest 将 Client 标记为 ready，仅供测试使用。
func (client *Client) SetReadyForTest() {
	if client == nil {
		return
	}
	client.ready.Store(true)
}
