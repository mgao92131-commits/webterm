package com.webterm.terminal.protocol;

import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

/** Baseline protobuf 与其消息字典的 protocol 边界对象。 */
public record WireBaseline(
    TerminalScreenV2Proto.Baseline message,
    WireDictionary dictionary
) {}
