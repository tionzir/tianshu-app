package dev.tianshu.host;

/**
 * 键盘弹出时**只把输入行抬起来**的计算 —— 纯逻辑，不碰 android 类，HostTest 直接断言
 *（android 侧的接线在 {@link KeyboardWatcher}，两者刻意分开：
 * 本项目立过规矩 ——「要被断言的逻辑不许挂在 Activity / View 上」，
 * 挤在一个类里 HostTest 一引用就 `NoClassDefFoundError: android/content/Context`）。
 *
 * 用户 2026-10-06：「对话的时候对话框要和键盘抬起来，但是背景图片不要抬起来」。
 * 为什么背景会动、以及为什么解法是「窗口钉死 + 自己抬输入行」，见 {@link KeyboardWatcher} 的类注释。
 */
public final class KeyboardLift {

    private KeyboardLift() {
    }

    /**
     * 输入行要自己抬起多少 px；**0 = 不用抬**（键盘没弹，或它本来就没被键盘盖住）。
     *
     * 判据是"输入行底 vs 键盘上沿"，不是"键盘高"：输入行下方本来就有悬浮底栏那一块留白
     * （{@link NavBar#SPACE_FOR_CONTENT_DP}），矮一点的键盘（浮动键盘 / 分屏）根本够不着它 ——
     * 那种情况下抬一下反而是"输入框在键盘上方老远飘着"。
     *
     * @param keyboardHeight 键盘高（px）；≤0 视为没弹
     * @param composerGap    输入行**未被抬起时**的底边、距页面底边的距离（px）
     * @param spacing        抬起后，输入行底与键盘上沿之间要留的缝（px）
     */
    public static int liftFor(int keyboardHeight, int composerGap, int spacing) {
        if (keyboardHeight <= 0 || composerGap < 0 || spacing < 0) return 0;
        int need = keyboardHeight - composerGap + spacing;
        return need > 0 ? need : 0;
    }

    /**
     * **只补滚动区**时要补多少 px —— 给"输入框在页面顶部、键盘够不着它"的页面用
     * （会话页的搜索框）：那里唯一要解决的是"最后一条被键盘盖住且滚不上来"，
     * 所以补满一个键盘高就够，页面上一动不动。
     *
     * @param keyboardHeight 键盘高（px）；≤0 视为没弹
     */
    public static int scrollPadFor(int keyboardHeight) {
        return keyboardHeight > 0 ? keyboardHeight : 0;
    }

    /**
     * 当前输入行**未被位移时**的底边（屏幕坐标）。
     *
     * ⚠️ 这里有个**真机上栽过的坑**：`getLocationOnScreen` 返回的位置**已经包含**我们施加的
     * `translationY`（位移是负的 ⇒ 屏幕 y 已经变小），所以要把它**加**回来才是"原位"。
     * 曾经写成"减去 appliedLift" —— 于是每帧多减一次：第一帧抬 676px，第二帧算出的候选值掉到 0，
     * 第三帧又抬 676px…… **逐帧振荡 = 用户看到的"一直会闪"**。
     * 真机日志（`2026-10-06 12:07:44`）里 `抬=676` 与 `抬=0` 逐帧交替，就是它。
     *
     * @param screenY     {@code getLocationOnScreen()[1]}（含已施加的位移）
     * @param height      输入行高
     * @param appliedLift 我们当前施加的位移量（正数，方向向上）
     */
    public static int composerBottomFrom(int screenY, int height, int appliedLift) {
        return screenY + height + appliedLift;
    }

    /**
     * 本次**想要**的补量：把输入行底抬到键盘上沿上方 {@code spacing} 处。
     *
     * 交给 {@link KeyboardGate} 决定要不要真的动手（系统在 resize、或键盘还在动画里时会否掉）。
     */
    public static int candidateLift(int rootBottom, int visibleBottom,
                                    int composerScreenY, int composerHeight,
                                    int appliedLift, int spacing) {
        int keyboard = rootBottom - visibleBottom;
        int composerBottom = composerBottomFrom(composerScreenY, composerHeight, appliedLift);
        return liftFor(keyboard, rootBottom - composerBottom, spacing);
    }

    /**
     * 兜底自抬**该不该动手** —— 三条同时成立才允许：
     *
     *   ① 键盘确实弹了（{@code keyboardHeight > 0}）；
     *   ② **系统没有在缩窗口**（{@code windowShrink <= 0}）—— 缩了就说明 `adjustResize` 正在生效，
     *      系统自己在抬输入框；我们再动一手就是**两边抢着摆同一个东西** ⇒ 输入框上下抖。
     *      （用户 2026-10-06 报的"一直会闪"就是这个。）
     *   ③ 键盘已稳定（{@code kbVisibleMs >= settleMs}）—— 弹出动画那两三百毫秒里可见区/窗口
     *      交替变化，此时的差值是**过渡帧**，照它动手会先推一下再撤回，同样是闪。
     *
     * @param keyboardHeight 键盘高（px）；≤0 视为没弹
     * @param windowShrink   页面底边相对"键盘弹出前"缩了多少 px（>0 = 系统在 resize）
     * @param kbVisibleMs    键盘已可见的时长（ms）；-1 = 当前不可见
     * @param settleMs       稳定阈值（ms）
     */
    public static boolean shouldSelfLift(int keyboardHeight, int windowShrink,
                                         long kbVisibleMs, int settleMs) {
        if (keyboardHeight <= 0) return false;
        if (windowShrink > 0) return false;
        if (kbVisibleMs < 0) return false;
        return kbVisibleMs >= (settleMs > 0 ? settleMs : 0);
    }
}
