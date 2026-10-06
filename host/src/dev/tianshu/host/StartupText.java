package dev.tianshu.host;

/**
 * 启动页的文案 —— 纯逻辑，不碰 Android，所以能在容器里直接断言。
 *
 * 为什么单独一个类：这些文案起初写在 {@link MainActivity} 里，但那是 android 类 ——
 * HostTest 一引用它就 `NoClassDefFoundError: android/app/Activity`
 * （测试运行时不带 android.jar），于是**断言悄悄没跑**：Java 直接崩在半路，
 * 而命令里的管道又把非零退出码吃了，看起来像绿的。比没有断言更坏。
 *
 * 所以规矩很简单：**要被 HostTest 断言的逻辑，不许挂在 Activity / View 上**。
 */
public final class StartupText {

    private StartupText() {
    }

    /**
     * 细节行文案：左边是可选的进度说明，右边固定跟一个"已等 N 秒"。
     *
     * "已等 N 秒"是解压几分钟期间"没卡死"的唯一证据；格式一旦漂掉（比如秒数不再更新），
     * 肉眼很难发现，但用户会以为 App 挂了。
     */
    public static String detailLine(String left, long seconds) {
        String waited = "已等 " + seconds + " 秒";
        return left == null || left.isEmpty() ? waited : left + " · " + waited;
    }

    /**
     * 转圈下面轮播的三句，**按顺序**循环。
     *
     * 第一句必须是最实在的那句（"首次启动…请耐心等待"）—— 用户头 6 秒看到它，
     * 才知道是要等而不是卡了；后两句是等久了才有意义的闲笔，排在后面。
     */
    public static final String[] ROTATING = {
            "首次启动可能需要一点时间，请耐心等待",
            "天机推演，稍候则明",
            "乾坤运转，请君稍待",
    };

    /** 轮播间隔（毫秒）。用户给的区间是 5～8 秒，取中值 6 秒。 */
    public static final long ROTATE_MS = 6000;

    /**
     * 已等待 elapsedMs 时该显示哪一句 —— 纯函数，所以顺序、循环、边界都能被断言。
     *
     * 负数（时钟回拨）按 0 处理：宁可多显示第一句，也不要抛异常或显示空白。
     */
    public static String rotateAt(long elapsedMs) {
        long ms = elapsedMs < 0 ? 0 : elapsedMs;
        int i = (int) ((ms / ROTATE_MS) % ROTATING.length);
        return ROTATING[i];
    }
}
