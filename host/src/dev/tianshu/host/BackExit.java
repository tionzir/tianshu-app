package dev.tianshu.host;

/**
 * 「再按一次退出」—— 主页面返回键的两次确认。
 *
 * 用户 2026-10-04 的原话：「我点击对话页面，然后点击外观页面，然后返回就会回到对话页面，
 * 不要这样，如果用户返回的话就提示要两次返回就退出 app 了，而不是返回回到上一个界面」。
 *
 * 纯逻辑、不碰 Android：把"这一下该不该退"做成一个可直接断言的函数，
 * 由 {@link BaseActivity#backExit()} 调用。{@code lastAt} 是 static ——
 * 跨页共享，所以"对话页按一下、设置页再按一下"仍算连按（底栏那三页本就是平级的同一层）。
 * 二级页（模型 / 面板 / 外观 …）不走这条：它们的返回是"回上一层"，见 {@code BaseActivity.onBackPressed}。
 */
public final class BackExit {

    private BackExit() {
    }

    /** 两次返回的间隔窗口（毫秒）。超过它就不算"连按"。 */
    public static final long WINDOW_MS = 2000;

    /** 上一次"第一下返回"的时刻。0 = 还没有过。 */
    private static long lastAt = 0;

    /**
     * 这一下返回该不该真的退出。
     *
     * @param nowMs 单调时钟（{@code SystemClock.uptimeMillis()}）
     * @return true = 窗口内第二次 → 退出；false = 第一次 → 已记时，界面该提示"再按一次"
     */
    public static synchronized boolean shouldExit(long nowMs) {
        if (lastAt != 0 && nowMs - lastAt <= WINDOW_MS) {
            lastAt = 0;          // 用完清零，免得下一次又立刻被算成"连按"
            return true;
        }
        lastAt = nowMs;
        return false;
    }

    /** 测试用：清掉计时。 */
    static synchronized void reset() {
        lastAt = 0;
    }
}
