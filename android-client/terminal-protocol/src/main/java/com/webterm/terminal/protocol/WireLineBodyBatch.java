package com.webterm.terminal.protocol;

import com.webterm.terminal.protocol.generated.TerminalHistoryProto;

/** HTTP LineBodyBatch protobuf 与 message-local 字典的 protocol 边界对象。 */
public record WireLineBodyBatch(
    TerminalHistoryProto.LineBodyBatchResponse message,
    WireDictionary dictionary
) {}
