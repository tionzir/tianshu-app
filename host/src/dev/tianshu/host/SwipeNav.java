package dev.tianshu.host;

import android.app.Activity;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

/**
 * 左右滑动切页 —— 在**底栏各页签之间**用横向滑动切换。
 *
 * 为什么在 Activity 层做、而不是 ViewPager：几个页各自是独立 Activity（对话页还要长期
 * 持有 SSE 连接），本项目又只依赖 android.jar（没有 androidx 的 ViewPager）。所以这里
 * 只做手势识别，命中就调 {@link NavBar#jump} —— 与点底栏是**同一个动作**，不引入第二套
 * 切页逻辑（切页动画、栈管理都不用重写）。
 *
 * 2026-10-06：外观页收进设置页后底栏只剩三格 —— 本类**一行没改**（目标格由
 * {@code Tabs.byId} 认，格数少了边界自然收紧：末格「设置」再左滑就是 {@code -1}）。
 * 二级页（current=-1，模型 / 面板 / 外观 …）不挂本类，也就不会参与切页。
 *
 * 判据（"横滑切页" vs "别的操作"）：
 *   ① 水平净位移够大（≥ {@link #MIN_DISTANCE_DP} dp）—— 太短是"没点准"，不是滑动；
 *   ② 水平净位移 ≥ 竖直净位移 × {@link #HORIZONTAL_RATIO} —— 斜着滚列表不该切页；
 *   ③ 发生在 {@link #MAX_DURATION_MS} 之内 —— 长按选字那种慢拖不该切页；
 *   ④ 不能起手在 SeekBar 上 —— 拖滑块是横向的，误切页就废了；
 *   ⑤ 不在边界（第一页再往右 / 最后一页再往左）。
 *
 * 用 DOWN→UP 的**净位移**判、不用速度：手机上一指慢划也要能切（fling 的速度阈值对
 * "慢慢划一下"的人不友好）。纯逻辑 {@link #targetIndex} 不碰 android 类，HostTest 直接断言。
 */
public final class SwipeNav {

    /** 触发切页的最小水平净位移（dp）。 */
    public static final int MIN_DISTANCE_DP = 60;
    /** 水平净位移至少要竖直净位移的这个倍数，才算"横滑"。 */
    public static final float HORIZONTAL_RATIO = 1.4f;
    /** 一次手势超过这个时长就不当滑动看（长按选字 / 拖拽都远长于它）。 */
    public static final int MAX_DURATION_MS = 1000;

    private final Activity activity;
    private float downX, downY;
    private long downAt;
    private boolean tracking;
    /** 这一页有没有滑块（null = 还没查过）—— 缓存它，省掉每次按下都爬一遍整棵树。 */
    private Boolean hasSeekBar;

    public SwipeNav(Activity activity) {
        this.activity = activity;
    }

    /** 把触摸事件喂进来（各页在 {@code dispatchTouchEvent} 里调，不拦截事件本身）。 */
    public void feed(MotionEvent e) {
        if (e == null) return;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downAt = e.getEventTime();
                // 起手压在滑块上 —— 那是在拖它，不是切页。
                //
                // 这里**曾**还有一条同类排除：起手落在可横滑容器（HorizontalScrollView）上时也不 tracking。
                // 移除于 2026-10-05：全项目已无 `new HorizontalScrollView`（对话页那行状态 chip 改成了
                // FlowRow/LinearLayout，不再横滑），该守卫恒不命中 —— 留着就成了"看起来像防护"的死代码。
                // ⚠ 什么条件下必须加回来：只要将来**任何**页面重新引入横向可滚的容器（HorizontalScrollView
                //   或自绘的横向滚动视图），就必须在这里恢复"起手在其上 → 不切页"的判定 ——
                //   当年真机踩过的症状是：想横滑该容器看全内容，结果被当成切页、一滑就跳到隔壁页。
                //   （HostTest 的 testSwipeNav 钉着这段注释，别把它一起删了。）
                tracking = !startsOnSeekBar(e);
                break;
            case MotionEvent.ACTION_UP:
                if (tracking) {
                    tracking = false;
                    if (e.getEventTime() - downAt <= MAX_DURATION_MS) {
                        float d = activity.getResources().getDisplayMetrics().density;
                        if (d <= 0f) d = 1f;
                        jumpIfNeeded((e.getX() - downX) / d, (e.getY() - downY) / d);
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                break;
            default:
                break;
        }
    }

    private void jumpIfNeeded(float dxDp, float dyDp) {
        int target = targetIndex(NavBar.currentFor(activity), dxDp, dyDp);
        if (target < 0) return;
        Tabs.Tab tab = Tabs.byId(target);
        if (tab != null) NavBar.jump(activity, tab);
    }

    /**
     * 纯逻辑：当前页 + 滑动净位移（dp）→ 目标页 id；不构成切页返回 -1。
     *
     * @param current 当前页 id（见 {@link Tabs}）；-1（二级页，如命令面板）一律不切
     * @param dxDp    水平净位移（dp，向右为正）
     * @param dyDp    竖直净位移（dp，向下为正）
     */
    public static int targetIndex(int current, float dxDp, float dyDp) {
        if (current < 0) return -1;
        if (Math.abs(dxDp) < MIN_DISTANCE_DP) return -1;
        if (Math.abs(dxDp) < Math.abs(dyDp) * HORIZONTAL_RATIO) return -1;
        int target = dxDp < 0 ? current + 1 : current - 1;   // 左滑（dx<0）→ 下一格；右滑 → 上一格
        return Tabs.byId(target) == null ? -1 : target;
    }

    /**
     * 起手点是否落在某个 SeekBar 上。
     *
     * 判据写成通用的：挂本类的页现在**都没有滑块**了（唯一带滑块的「卡片透明度」在外观页，
     * 而外观页 2026-10-06 起是二级页、不挂本类）—— 留着是因为这条守卫一旦真用上就是硬伤，
     * 而它只在"这一页确实有滑块"时才付出遍历代价（见下）。
     *
     * ⚠ 先问一句"这一页到底有没有滑块"：没有就一次全树遍历都省了。
     * 每次 ACTION_DOWN 都爬一遍整棵 View 树，在对话页（几百个 View）上是纯浪费。
     * 结果缓存起来 —— 页面重画只会让缓存从"无"变"有"，不会反过来。
     */
    private boolean startsOnSeekBar(MotionEvent e) {
        if (hasSeekBar == null) {
            View content = activity.findViewById(android.R.id.content);
            hasSeekBar = Boolean.valueOf(content != null && hasSeekBar(content));
        }
        if (!hasSeekBar.booleanValue()) return false;
        View content = activity.findViewById(android.R.id.content);
        return content != null && hitsSeekBar(content, e.getRawX(), e.getRawY());
    }

    /** 页面上有没有 SeekBar。 */
    private static boolean hasSeekBar(View v) {
        if (v instanceof SeekBar) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasSeekBar(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static boolean hitsSeekBar(View v, float rawX, float rawY) {
        if (v instanceof SeekBar) {
            Rect r = new Rect();
            if (v.getGlobalVisibleRect(r) && r.contains((int) rawX, (int) rawY)) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hitsSeekBar(g.getChildAt(i), rawX, rawY)) return true;
            }
        }
        return false;
    }
}
