package dev.tianshu.host;

import java.util.Calendar;
import java.util.Locale;

/**
 * 聊天流里的**时间分割条**文案（纯逻辑，不碰 Android）。
 *
 * ## 它管什么、不管什么
 * 管：**对话正文里**两条消息之间插的那条"昨天 14:30"，让人一眼知道那是多久以前说的话。
 * 不管：会话列表右侧那个时间 —— 那个**必须保持绝对时间**。用户 2026-10-03 的真机反馈
 * 明确要求过"会话时间改绝对"（见提交 `78c59e1`），落地在 {@link SessionList#absTime}。
 * ⚠️ 别看到参考 App 的 `TimeLabels.forList` 给"昨天 / 周三"，就把会话列表也改回去 ——
 * 那是在推翻用户点名要过的东西。
 *
 * ## 为什么单独一个纯逻辑类
 * "今天 / 昨天 / 一周内 / 今年 / 跨年"全是**边界敏感**的判断：跨天、跨周、跨年各一条分支，
 * 而在真机上没法穷举（那要改系统时间）。做成纯函数之后，边界由 HostTest 钉死。
 *
 * ## 两条实现纪律（对抗复核指出，改代码时别丢）
 *   ① **星期名手写**，不用 `SimpleDateFormat("EEE")` —— 测试与运行都在 `LC_ALL=C.UTF-8` 下，
 *      它会输出 `Wed`，断言必红；
 *   ② "今天/昨天"按**本地日历日**算，不是"距现在 24 小时内" —— 后者在容器（UTC）与设备
 *      （UTC+8/+7）之间测不出差异，是**假绿**。
 */
public final class TimeLabels {

    private TimeLabels() {
    }

    /**
     * 两条消息间隔超过它，才值得插一条分割条。
     *
     * 5 分钟是微信量级：连续对话（一问一答几秒、几十秒）不插，中间隔了一会儿才插 ——
     * 插太密会把聊天记录切得支离破碎。
     */
    public static final long DIVIDER_GAP_MS = 5 * 60 * 1000L;

    /** 手写的星期名（`Calendar.DAY_OF_WEEK` 是 1=周日 … 7=周六）。 */
    private static final String[] WEEKDAYS = {
            "周一", "周二", "周三", "周四", "周五", "周六", "周日",
    };

    /**
     * 分割条文案。
     *
     * <pre>
     *   今天        → 14:30
     *   昨天        → 昨天 14:30
     *   一周内      → 周三 14:30
     *   今年更早    → 9月20日 14:30
     *   跨年        → 2025年12月31日 14:30
     * </pre>
     *
     * 比"会话列表"多带一个时刻 —— 聊天流里光写"周三"没法定位。
     *
     * @param at  消息时刻；`<= 0` = **时间未知**（加时间戳字段之前存下的老消息）
     * @return 文案；时间未知时返回**空串** —— 宁可没有时间，也不要显示一个 1970 年的荒谬时间
     */
    public static String forDivider(long at, long now) {
        if (at <= 0) return "";
        Calendar t = cal(at);
        Calendar n = cal(now);
        long days = dayDiff(t, n);
        String hm = clock(t);

        if (days <= 0) return hm;                       // 今天（含时钟回拨算出的"未来"）
        if (days == 1) return "昨天 " + hm;
        if (days < 7) return weekday(t) + " " + hm;
        if (t.get(Calendar.YEAR) == n.get(Calendar.YEAR)) {
            return (t.get(Calendar.MONTH) + 1) + "月" + t.get(Calendar.DAY_OF_MONTH) + "日 " + hm;
        }
        return t.get(Calendar.YEAR) + "年" + (t.get(Calendar.MONTH) + 1) + "月"
                + t.get(Calendar.DAY_OF_MONTH) + "日 " + hm;
    }

    /**
     * 这条消息前面该不该插分割条。
     *
     * 判据是**间隔**，不是"跨没跨天"：半夜 23:58 与 00:01 只差 3 分钟，不该被切成两段；
     * 而同一个下午里隔了两小时的两句话，该插。
     *
     * @param prevAt 上一条**有**时间戳的消息（`0` = 还没有）
     * @param curAt  当前这条的时刻
     */
    public static boolean needsDivider(long prevAt, long curAt) {
        if (curAt <= 0) return false;              // 这条时间未知 → 不插（没东西可显示）
        if (prevAt <= 0) return true;              // 它是目前已知的最早时刻 → 插一条
        return curAt - prevAt >= DIVIDER_GAP_MS;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 两个时刻相差几个**本地日历日**。
     *
     * 用"当地 0 点"的毫秒差再取整：夏令时切换那天只有 23 或 25 小时，
     * 直接拿毫秒差除以 86400000 会算错一天。
     */
    static long dayDiff(Calendar at, Calendar now) {
        return Math.round((midnight(now) - midnight(at)) / 86400000.0);
    }

    private static long midnight(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, 0);
        x.set(Calendar.MINUTE, 0);
        x.set(Calendar.SECOND, 0);
        x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    private static Calendar cal(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c;
    }

    private static String clock(Calendar c) {
        return String.format(Locale.US, "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** `Calendar.DAY_OF_WEEK`：1=周日 … 7=周六 → 手写表的 0=周一 … 6=周日。 */
    private static String weekday(Calendar c) {
        int d = c.get(Calendar.DAY_OF_WEEK);
        return WEEKDAYS[d == Calendar.SUNDAY ? 6 : d - 2];
    }
}
