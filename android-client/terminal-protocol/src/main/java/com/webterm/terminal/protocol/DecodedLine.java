package com.webterm.terminal.protocol;

import com.webterm.terminal.model.LineBody;
import com.webterm.terminal.model.LineKey;

/** 字典已解析的 line envelope；位置仍与纯正文分离。 */
public record DecodedLine(LineKey key, long historySeq, LineBody body) {}
