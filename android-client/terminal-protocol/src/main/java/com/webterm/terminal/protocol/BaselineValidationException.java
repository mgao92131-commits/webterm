package com.webterm.terminal.protocol;

/** 携带稳定 fault code 的 Baseline 校验异常。 */
public final class BaselineValidationException extends IllegalArgumentException {
  public final BaselineFaultCode faultCode;

  public BaselineValidationException(BaselineFaultCode faultCode) {
    super(faultCode == null ? "invalid Baseline" : faultCode.name());
    this.faultCode = faultCode == null
        ? BaselineFaultCode.INVALID_LINE_BODY : faultCode;
  }

  public BaselineValidationException(BaselineFaultCode faultCode, Throwable cause) {
    super(faultCode == null ? "invalid Baseline" : faultCode.name(), cause);
    this.faultCode = faultCode == null
        ? BaselineFaultCode.INVALID_LINE_BODY : faultCode;
  }
}
