package dev.tianshu.host;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * 所有页面的基类 —— 只做一件事：**把整页按 {@link Ui#SCALE} 等比缩放**。
 *
 * 用户 2026-10-04 的原话：「各个界面缩放 2%，并且自适应，不要紧凑那种」。
 * 三个词各对应一处设计：
 *
 *   - **缩放 2%**：改的是 {@code Configuration.densityDpi}。dp 与 sp 一起缩，
 *     所以字号、间距、图标、控件**整体**小 2% —— 而不是把内边距压小那种"紧凑"
 *     （那正是前几轮被否过的做法）。
 *   - **自适应**：缩放比例是一个常量，**不是**按某块屏算出来的固定像素值。
 *     换机型、换屏幕密度，2% 依然成立；物理像素宽不变，于是可用 dp 宽度自动变大
 *     （元素缩小 + 可放下更多），这正是"自适应"的含义。
 *   - **不要紧凑**：没有任何一个地方去改 padding / margin / 字号常量，只改了"dp 有多长"。
 *     把它改成 1.0 就回到原样，不牵动任何布局代码。
 *
 * 实现落在 {@code attachBaseContext}：Activity 在 onCreate 之前用它包一层带新 Configuration
 * 的 Context，之后整页的 getResources() 都走新密度。这是 Android 上做"系统级显示缩放"
 * 的标准做法（系统设置里的「显示大小」也是调这个值）。
 *
 * ⚠️ 纯逻辑测试（HostTest）碰不到这里 —— 它是 android 类。缩放效果只能靠真机看。
 */
public class BaseActivity extends Activity {

    /** 兜底下限：密度不能被缩到不可用（72dpi 已接近 ldpi 的极限）。 */
    private static final int MIN_DPI = 72;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(scale(base));
    }

    /**
     * 按 {@link Ui#SCALE} 调一版 Configuration 包在 Context 外面。
     *
     * 任何一步失败都**原样返回 base** —— 缩放是观感，不该因为一个取不到的 Configuration
     * 就让整页开不起来（fail-open 在这里是对的：大不了不缩，而不是黑屏）。
     */
    static Context scale(Context base) {
        if (base == null) return null;
        try {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            int next = Math.round(c.densityDpi * Ui.SCALE);
            if (next < MIN_DPI) next = MIN_DPI;
            if (next == c.densityDpi) return base;    // 没变化就别包一层（省开销）
            c.densityDpi = next;
            return base.createConfigurationContext(c);
        } catch (Throwable t) {
            return base;
        }
    }

    // ---------------------------------------------------------------- 状态栏：透出 app 背景

    /**
     * 让**系统状态栏**透出 app 自己的背景（用户 2026-10-04：「app 上面的状态栏也要让我的 app
     * 背景填充并且有透明度」）。
     *
     * 做法是标准的 edge-to-edge：状态栏底色置透明 + 内容延伸进状态栏区域
     *（{@code LAYOUT_FULLSCREEN}）。于是 {@link Theming} 挂在 content 上的背景
     *（渐变 / 背景图 / 遮罩）会一直铺到屏幕最顶端，状态栏像是"浮在背景上"、带着透明度。
     *
     * 内容不能真被状态栏压住 —— 那由 {@link #setContentView(View)} 给根布局补一段顶部内边距解决。
     * 深色主题下状态栏图标保持浅色、浅色主题下改成深色（{@code LIGHT_STATUS_BAR}，API 23+），
     * 否则白底白字看不见。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyStatusBar();
    }

    /**
     * 每次进入页面记一行轨迹 —— 这一行是「卡在哪一页 / 崩在哪一页」的直接答案。
     *
     * 为什么记在基类：崩溃报告里只有调用栈，而栈顶常常是框架代码；真正有用的是
     * "用户当时在哪一页"。八个页面各写一遍迟早漏一个。
     * {@link Diagnostics} 的类注释讲了为什么必须把这类信息写到共享存储：
     * 外部根本看不到设备内部。
     */
    @Override
    protected void onResume() {
        super.onResume();
        Diagnostics.notePage(getClass().getSimpleName());
    }

    /**
     * 状态栏图标明暗 —— 实现搬去 {@link Theming#applyStatusBar} 了。
     *
     * 为什么搬（C1，2026-10-05 修）：这段以前只在 {@code onCreate} 跑一次，而主题在**运行中**变化时
     * （外观页把浅色换成深色后返回、或被 {@code uiMode} 接管的启动页）页面不重走 onCreate，
     * 状态栏图标就停在旧主题的明暗上。放进 {@link Theming}，{@code Theming.refresh()} 才能在
     * 每条刷新路径（onResume / onConfigurationChanged）上一起调。这里保留一层转发，页面代码零改动。
     */
    private void applyStatusBar() {
        Theming.applyStatusBar(this, Theming.tokens(this));
    }

    /**
     * 根布局补一段**状态栏高度**的顶部内边距 —— 内容不被状态栏压住，背景却铺到顶。
     *
     * 在 {@code setContentView} 里做而不是各页各写一遍：八个页面都会走这里，
     * 少一处漏了就那一页的标题被状态栏盖住。用**加**而不是覆盖，是为了不吞掉页面
     * 自己给 root 设过的那点内边距（如命令面板）。
     */
    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        if (view == null) return;
        int sb = statusBarHeight();
        if (sb <= 0) return;
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop() + sb,
                view.getPaddingRight(), view.getPaddingBottom());
    }

    /** 系统状态栏高度（px）；取不到返回 0（那就等于不补，退回"内容从状态栏下开始"的老样子）。 */
    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 生命周期守卫

    /** 页面还活着吗 —— 后台回调碰视图树之前先问一句。 */
    protected boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    /**
     * **带存活检查的主线程投递** —— 取代裸的 {@code runOnUiThread}。
     *
     * 为什么需要它：页面 finish 之后，后台线程的回调还在主线程队列里，原先会照常去改
     * 一棵已经没了的视图树（内存泄漏，偶尔还崩）。而 {@code Activity.runOnUiThread} 是
     * **final**、override 不了（2026-10-04 试过），所以做成这个显式入口，页面里统一改调它。
     */
    protected void postUi(Runnable action) {
        if (action == null) return;
        super.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (alive()) action.run();
            }
        });
    }

    // ---------------------------------------------------------------- 返回键（两次退出）

    /**
     * **主页面**的返回键 = 退出 App，不回上一个页面。
     *
     * 用户 2026-10-04 的原话：「我点击对话页面，然后点击外观页面，然后返回就会回到对话页面，
     * 不要这样，如果用户返回的话就提示要两次返回就退出 app 了，而不是返回回到上一个界面」。
     * 底栏各 tab 靠 {@code FLAG_ACTIVITY_REORDER_TO_FRONT} 互跳，系统默认返回会「回到上一个 tab」
     * —— 那不是用户要的语义（tab 之间是平级，不该有返回栈）。
     *
     * 只对**主页面**（底栏那几格）生效：二级页（命令面板 / 模型 / 面板 / 外观）仍走系统默认返回上一页
     * —— 那些页面本来就"点进去还有上一层"，返回它们才是对的。
     */
    @Override
    public void onBackPressed() {
        if (NavBar.currentFor(this) >= 0) {
            backExit();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 两次返回才退出：第一下提示，两秒内再按一下才真的走。
     * 退出用 {@code moveTaskToBack}（回桌面）而不是杀进程 —— 常驻的 serve 保留着，切回来即用。
     */
    protected void backExit() {
        if (BackExit.shouldExit(android.os.SystemClock.uptimeMillis())) {
            moveTaskToBack(true);
        } else {
            android.widget.Toast.makeText(this, "再按一次退出天枢",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
