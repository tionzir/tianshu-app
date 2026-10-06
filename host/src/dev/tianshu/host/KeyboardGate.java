package dev.tianshu.host;

/**
 * 键盘让位的**决策状态机** —— 纯逻辑，不碰 android 类，HostTest 用"事件序列"直接断言
 *（`审计.md §17/§18`）。
 *
 * 为什么单独抽出来：判断"该不该动手"本身简单，难的是**它在一串观测里的行为**——
 * 用户报的"一直会闪"不是某一步算错，而是**整段序列里的动作序列在抖**（推上去又撤回来）。
 * 把状态收进一个小对象，就能在宿主侧拿一串 `(页面底边, 可见区底边, 时刻)` 喂进去，
 * 断言"整段序列的动作次数 ≤ 抬起 + 复位"，而不必靠真机反复试。
 *
 * 闸门规则见 {@link KeyboardLift#shouldSelfLift}：**系统在 resize（窗口被缩）** 或 **键盘还在动画里**
 * 时一律不动手 —— 抢着摆同一个东西就是闪。
 *
 * 线程：只在主线程（布局回调）里用，不加锁。
 */
public final class KeyboardGate {

    /** 键盘已可见多久才算"稳定"（ms）。弹出动画约 200–300ms。 */
    private final int settleMs;
    /** 键盘**首次被看见**的时刻；0 = 当前不可见。 */
    private long kbSince;
    /** 当前已施加的补量 px（0 = 没动手）。 */
    private int applied;

    public KeyboardGate(int settleMs) {
        this.settleMs = settleMs;
    }

    /**
     * 喂一次观测。
     *
     * @param keyboardHeight 键盘高（页面底边 − 可见区底边）；≤0 视为没弹
     * @param windowShrink   页面底边相对"键盘弹出前"缩了多少（>0 = 系统正在 resize）
     * @param nowMs          单调时钟（{@code SystemClock.uptimeMillis()}）
     * @param candidatePad   若允许动手，本次想要的补量（px）
     * @return **-1 = 本次无动作**（调用方别碰 UI）；否则是本次要施加的补量（0 = 复位）
     */
    public int feed(int keyboardHeight, int windowShrink, long nowMs, int candidatePad) {
        if (keyboardHeight <= 0) kbSince = 0;
        else if (kbSince == 0) kbSince = nowMs;
        long visibleMs = kbSince == 0 ? -1 : nowMs - kbSince;

        int want = KeyboardLift.shouldSelfLift(keyboardHeight, windowShrink, visibleMs, settleMs)
                ? Math.max(0, candidatePad)
                : 0;
        if (want == applied) return -1;        // 没变化就别动（避免每帧 setPadding 触发重排）
        applied = want;
        return applied;
    }

    /** 当前已施加的补量（0 = 没动手）—— 诊断与断言用。 */
    public int applied() {
        return applied;
    }

    /** 键盘已可见的时长（ms）；-1 = 当前不可见 —— 诊断用。 */
    public long visibleMs(long nowMs) {
        return kbSince == 0 ? -1 : nowMs - kbSince;
    }
}
