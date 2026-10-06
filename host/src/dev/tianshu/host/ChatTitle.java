package dev.tianshu.host;

/**
 * 对话页页头标题 —— **从用户第一句话来**（用户 2026-10-04：「对话页面上面对话那两个字，
 * 要看用户第一句发什么而改变，像会话页面那个卡片的标题一样，从第一句开始更新」）。
 *
 * 纯逻辑、不碰 Android —— 所以能直接被 HostTest 断言（换行、空白、超长截断这些边界
 * 最容易出错，也正是最该被钉住的地方）。
 *
 * 与「会话」页的卡片标题同源：那边显示的是服务端给的会话名（就是第一句），
 * 这里在**新会话**场景下自己先从第一句推一个出来，让页头立刻跟上，不必等服务端回填。
 */
public final class ChatTitle {

    private ChatTitle() {
    }

    /** 还没起标题时的默认页头。 */
    public static final String DEFAULT = "对话";

    /**
     * 一句话 → 页头标题。
     *
     * 规则：换行/连续空白**压成一个空格**再 trim（多行输入不能把页头撑成两行）；空 → {@link #DEFAULT}。
     *
     * **不在这里按字数截断** —— 能显示多少由**控件宽度**决定（页头走 `singleLine + ellipsize=END`）。
     * 原先按 char 截到 {@code MAX_CHARS} 有两个毛病（见审计 §2.4）：
     *   ① 与宽度无关 ——「Greeting to Tianshu」在还剩一半宽度时就被截成「Greeting to …」，
     *      而**同一个标题**在会话列表卡片里是完整的；
     *   ② 宽窄字形一视同仁 —— 12 个汉字逼近 22sp 下的可用宽度，12 个西文只用到一半。
     *
     * 不做 markdown / 命令解析 —— 那是别处的职责，这里只负责"能当标题用"。
     */
    public static String fromMessage(String text) {
        if (text == null) return DEFAULT;
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(c);
        }
        String s = sb.toString();
        return s.isEmpty() ? DEFAULT : s;
    }

    /** 把任意来源的标题收拾干净（服务端的会话标题可能带空白 / 换行）；空则回默认。 */
    public static String clean(String title) {
        String s = fromMessage(title);
        return s == null || s.isEmpty() ? DEFAULT : s;
    }
}
