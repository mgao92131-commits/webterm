package com.webterm.terminal.protocol;

import com.webterm.terminal.protocol.generated.TerminalScreenV2Proto;

/** TerminalCommit protobuf 与字典增量的 protocol 边界对象。 */
public record WireTerminalCommit(
    TerminalScreenV2Proto.TerminalCommit message,
    WireDictionary dictionaryAdditions
) {}
