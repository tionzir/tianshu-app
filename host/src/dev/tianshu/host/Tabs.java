package dev.tianshu.host;

/**
 * 底部页面栏的页签定义 —— **纯数据，不碰 Android**，所以 HostTest 能直接断言。
 *
 * 为什么单独放一个纯逻辑类：{@link NavBar} 是 android 类（它要建 View），
 * HostTest 一引用它就 `NoClassDefFoundError: android/app/Activity`。
 * 上一轮刚踩过 —— **要被断言的逻辑不许挂在 Activity / View 上**。
 *
 * 顺序即显示顺序：对话 · 会话 · 设置 · 个人。
 *
 * 2026-10-06：**「外观」不再是底栏一格** —— 用户要求「把外观页面放到设置页面里做成一个卡片，
 * 点进卡片才是外观页面」。外观页因此降成二级页（设置 → 外观），这里少一格、
 * {@link NavBar} 从四格变三格。别再把外观页塞回底栏 —— 设置页那张卡与底栏这一格是
 * 同一页的两个门（HostTest 的 `testTabs` 钉着这一点）。
 *
 * 2026-10-06 同日再改：用户点名「这个 app 还差一个个人页面」→ 补第 4 格「个人」
 * （官方 profile 面的 App 落点：星籍 / 登录时长 / token 用量 / 星域用量 / 仓库）。
 * 于是底栏又回到四格 —— 但**顺序变了**：设置仍在第 3 格，个人是**末格**。
 * 别按"外观那格的老位置"推断 id：老 `APPEARANCE = 3` 已删，`PROFILE = 3` 是它的继任者，
 * 两者语义完全不同（`testTabs` 逐格断言 label 与 id）。
 */
public final class Tabs {

    public static final int CHAT = 0;
    public static final int SESSIONS = 1;
    public static final int SETTINGS = 2;
    /** 个人 —— 官方 profile 面的落点（2026-10-06 补，见类注释）。 */
    public static final int PROFILE = 3;
    // 原 `APPEARANCE = 3` 已删（2026-10-06，见类注释）—— 外观页不再是底栏一格。

    /** 一个页签。`activity` 用全限定类名而不是 Class —— 测试侧不该碰 android 类。 */
    public static final class Tab {
        public final int id;
        /**
         * 图标种类（{@link Icon} 的常量）。
         *
         * 第三轮从"一个字符"改成"一个图标"：符号的字形覆盖依赖设备字体 ——
         * 334 个字体里 `☰`/`✦`/`◐` 都只有 1 个覆盖，换个 ROM 就可能集体消失。
         * 这是 `static final int`，**编译期就内联**了，所以本类不会因此去加载 android 类
         *（它必须保持"纯数据、能直接被 HostTest 断言"，见类注释）。
         */
        public final int icon;
        public final String label;
        public final String activity;

        Tab(int id, int icon, String label, String activity) {
            this.id = id;
            this.icon = icon;
            this.label = label;
            this.activity = activity;
        }
    }

    public static final Tab[] ALL = {
            new Tab(CHAT, Icon.STAR, "对话", "dev.tianshu.host.ChatActivity"),
            new Tab(SESSIONS, Icon.MENU, "会话", "dev.tianshu.host.SessionListActivity"),
            new Tab(SETTINGS, Icon.GEAR, "设置", "dev.tianshu.host.SettingsActivity"),
            new Tab(PROFILE, Icon.PERSON, "个人", "dev.tianshu.host.ProfileActivity"),
            // 「外观」那格 2026-10-06 移走（见类注释）—— 它现在是设置页里的一张卡。
            // 图标 `Icon.HALF` 没删：设置页那张卡用的还是它。
    };

    private Tabs() {
    }

    /** 按 Activity 全限定名找页签下标；找不到返回 -1（比如命令面板这类二级页面）。 */
    public static int indexOfActivity(String activityClassName) {
        if (activityClassName == null) return -1;
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].activity.equals(activityClassName)) return i;
        }
        return -1;
    }

    public static Tab byId(int id) {
        for (Tab t : ALL) {
            if (t.id == id) return t;
        }
        return null;
    }
}
