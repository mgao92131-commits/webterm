package com.webterm.terminal.renderer;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/** {@link TerminalCopyBeautifier} 的纯 JVM 单元测试。 */
public class TerminalCopyBeautifierTest {

  @Test
  public void joinsIndentedListContinuation() {
    assertEquals("- C1 第一行第二行", beautify(
        new CopyLine("- C1 第一行", false, false),
        new CopyLine("  第二行", false, true)));
  }

  @Test
  public void keepsMultipleListItemsOnSeparateLines() {
    assertEquals("- C1 第一项续行\n- C2 第二项", beautify(
        new CopyLine("- C1 第一项", false, false),
        new CopyLine("  续行", false, true),
        new CopyLine("- C2 第二项", false, false)));
  }

  @Test
  public void addsSpaceBetweenAsciiAndChinese() {
    assertEquals("TerminalSessionArgs 加身份字段", beautify(
        new CopyLine("TerminalSessionArgs", true, false),
        new CopyLine("加身份字段", false, false)));
  }

  @Test
  public void doesNotAddSpaceBetweenChinese() {
    assertEquals("安全加固", beautify(
        new CopyLine("安全", true, false),
        new CopyLine("加固", false, false)));
  }

  @Test
  public void handlesParenthesesAndPunctuation() {
    assertEquals("connectionKey（删 UploadConnectionKeys）、通知", beautify(
        new CopyLine("connectionKey（删", true, false),
        new CopyLine("UploadConnectionKeys）、通知", false, false)));
  }

  @Test
  public void keepsShortTitleSeparateFromList() {
    assertEquals("各提交内容\n- C1 第一项", beautify(
        new CopyLine("各提交内容", false, false),
        new CopyLine("- C1 第一项", false, false)));
  }

  @Test
  public void joinsExplicitWrapWithoutIndentation() {
    assertEquals("TerminalSessionArgs 加身份字段", beautify(
        new CopyLine("TerminalSessionArgs", true, false),
        new CopyLine("加身份字段", false, false)));
  }

  @Test
  public void trimsTrailingPaddingAndSkipsBlankLines() {
    assertEquals("标题\n内容", beautify(
        new CopyLine("标题       ", false, false),
        new CopyLine("          ", false, true),
        new CopyLine("内容       ", false, false)));
  }

  @Test
  public void formatsTheConnectionIdentityCommitSummary() {
    assertEquals(
        "各提交内容\n"
            + "- C1 统一连接身份：DeviceConnectionKeys.resolve() 唯一入口 + 协调器全部终端入口贯穿 direct:{configId}。\n"
            + "- C2 终端/上传/通知按 key 路由：TerminalSessionArgs 加身份字段、工厂分流、上传复用 connectionKey（删 UploadConnectionKeys）、通知 Resolver Direct 分支。\n"
            + "- C3 编辑/删除保身份：updateDirectDevice 保 configId、去重排除自身、Registry 地址变化重建、删除补全本地清理。\n"
            + "- C4 Agent 安全加固：allowInsecureRemote + 默认回环地址、原子 Token 旋转、登录限流、HTTP 超时、Android 风险提示。\n"
            + "- C5 文档：agent-config.md 安全加固说明。\n"
            + "关键处理",
        beautify(
            new CopyLine("各提交内容", false, false),
            new CopyLine("  - C1 统一连接身份：DeviceConnectionKeys.resolve() 唯一入口 + 协调器全部终端入口贯穿 direct:{configId}。", false, true),
            new CopyLine("  - C2 终端/上传/通知按 key 路由：TerminalSessionArgs", false, true),
            new CopyLine("  加身份字段、工厂分流、上传复用 connectionKey（删", false, true),
            new CopyLine("  UploadConnectionKeys）、通知 Resolver Direct 分支。", false, true),
            new CopyLine("  - C3 编辑/删除保身份：updateDirectDevice 保", false, true),
            new CopyLine("  configId、去重排除自身、Registry", false, true),
            new CopyLine("  地址变化重建、删除补全本地清理。", false, true),
            new CopyLine("  - C4 Agent 安全加固：allowInsecureRemote +", false, true),
            new CopyLine("  默认回环地址、原子 Token 旋转、登录限流、HTTP", false, true),
            new CopyLine("  超时、Android 风险提示。", false, true),
            new CopyLine("  - C5 文档：agent-config.md 安全加固说明。", false, true),
            new CopyLine("关键处理", false, false)));
  }

  private static String beautify(CopyLine... lines) {
    return TerminalCopyBeautifier.beautify(Arrays.asList(lines));
  }
}
