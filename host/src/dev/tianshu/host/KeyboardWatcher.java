package dev.tianshu.host;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;

/**
 * 键盘弹出时让**页面不动、各补各的** —— android 侧接线；
 * 要补多少的纯计算在 {@link KeyboardLift}（那个类不碰 android，好让 HostTest 断言）。
 *
 * 用户 2026-10-06：「对话的时候对话框要和键盘抬起来，但是背景图片不要抬起来」→「会话页也改吧」
 * →「对话页还是没改呀，我点对话框他还是不会弹起来，我打字都不知道我打了什么」。
 *
 * ## 谁负责什么（第三版，前两版都被真机否掉，教训写在这里）
 * 「输入框抬起」与「背景不动」是**两件事**，得各找一个**不依赖猜测**的靠山：
 *
 * | 诉求 | 靠谁 | 为什么不是别的 |
 * |---|---|---|
 * | 输入框抬起 | **系统 resize**（manifest `adjustResize`） | 曾试过 `adjustNothing` + 自己读键盘高，**错**：该设备在 adjustNothing 下不更新可见区，读出来的键盘高是 0 ⇒ 输入框被盖（用户第二次报的原话） |
 * | 背景不动 | **绘制层按固定全屏高画**（{@link Backdrop#extentFor}，见 {@link Theming}） | 曾把它寄托在"窗口别动"上 —— 那是**间接**的：窗口一动就得靠模式不出错。绘制层固定高度是直接判据，跟让位方式解耦 |
 * | 谁都不许 pan | manifest 不写 `adjustPan` | pan 推的是**整个窗口**，背景、内容一起走 —— 用户第一次报的"背景图片被抬起来" |
 *
 * 那么本类还干什么？
 *   - **兜底**：万一 resize 没生效，就按可见区差自抬输入行（窗口已 resize 时这个差≈0，本类自动不动作）；
 *   - **诊断**：每次变化写一行到 {@link BootLog}（页底/可见底/键盘/抬多少/窗高变化）——
 *     这一行是"到底哪种情况"的**现场证据**，用户从设置页「运行日志」就能取出来，不必连 adb。
 *
 * ⚠️ 代价（写清，别当成 bug 去"修"）：resize 生效时窗口会变矮，悬浮底栏跟着窗口底边上移。
 */
public final class KeyboardWatcher {

    /**
     * 键盘弹出动画大约 200–300ms。在这段时间里可见区与窗口尺寸**交替**变化，
     * 照中间帧的差值动手会"先推一下再撤回" —— 那就是闪。所以要求键盘稳定这么久才允许兜底动手。
     */
    private static final int SETTLE_MS = 250;

    /**
     * 聚焦期间的重算间隔（ms）。为什么值得**轮询**：这台设备上键盘弹出**不引起窗口 resize**
     *（日志 `窗高变化=0`）⇒ 可能一次全局布局都没有、`onFocusChange` 也不会再来（第二次点时已聚焦）
     * —— 那就没有任何事件能叫醒我们。轮询是唯一不依赖系统事件语义的触发源；
     * 开销可忽略（一次 `getLocationOnScreen` + 一次可见区查询 + 几个整数运算，每秒约 7 次），
     * 且**只在输入框聚焦、窗口有焦点时**跑（失焦/后台自停）。
     */
    private static final int POLL_MS = 150;

    private KeyboardWatcher() {
    }

    /**
     * 把**固定底部的输入行**抬到键盘上方（resize 没生效时的兜底），并同步给滚动容器补底部内边距。
     *
     * @param pageRoot 页面根布局（`setContentView` 传的那个）—— 它的底边就是"页面底边"
     * @param scroller 消息区（同步加底部内边距）
     * @param composer 输入行（万不得已要自抬时，**唯一**被位移的视图）
     */
    public static void attach(final Activity a, final View pageRoot, final View scroller,
                              final View composer) {
        watch(a, pageRoot, scroller, composer);
    }

    /**
     * **只**给滚动容器补底部内边距（不位移任何视图）。
     *
     * 给"输入框在页面顶部、键盘够不着"的页面用（会话页的搜索框）：那里唯一要解决的是
     * "列表最后一条被键盘盖住且滚不上来"，所以补满一个键盘高就够，页面上一动不动。
     */
    public static void attachScrollOnly(final Activity a, final View pageRoot, final View scroller) {
        watch(a, pageRoot, scroller, null);
    }

    private static void watch(final Activity a, final View pageRoot, final View scroller,
                              final View composer) {
        final View decor = a.getWindow().getDecorView();
        final int pad0 = scroller.getPaddingBottom();
        final int spacing = Theming.dp(a, Ui.S2);
        /** 键盘弹出**之前**的"页面底边"。跟它比才知道窗口是不是被 resize 了 —— 也是自抬的闸门之一。 */
        final int[] baseRootBottom = {0};
        /** 「该不该动手 / 要不要复位」的整套状态收在这里（纯逻辑，HostTest 用事件序列断言它）。 */
        final KeyboardGate gate = new KeyboardGate(SETTLE_MS);
        final int[] rootLoc = new int[2];
        final int[] composerLoc = new int[2];

        final Runnable recompute = new Runnable() {
            @Override
            public void run() {
                pageRoot.getLocationOnScreen(rootLoc);
                int rootBottom = rootLoc[1] + pageRoot.getHeight();
                // 布局还没落定时（高度/位置为 0）别算 —— 否则会按"键盘占了整屏"处理
                if (rootBottom <= 0) return;
                if (baseRootBottom[0] == 0 || rootBottom > baseRootBottom[0]) {
                    baseRootBottom[0] = rootBottom;      // 记住"没被键盘压过"的页面底边
                }

                Rect vis = new Rect();
                decor.getWindowVisibleDisplayFrame(vis);
                int keyboard = rootBottom - vis.bottom;
                int shrink = baseRootBottom[0] - rootBottom;     // >0 ⇒ 系统正在 resize（在替我们让位）

                // 想抬多少（只在闸门放行时才会被采纳；闸门见 KeyboardGate）
                int candidateLift = 0;
                if (composer != null) {
                    composer.getLocationOnScreen(composerLoc);
                    if (composer.getHeight() <= 0) return;
                    // ⚠️ 底边的算法在 KeyboardLift.composerBottomFrom 里（含"位置已含位移、
                    //    要把它加回来"这条 —— 写反过一次，真机上表现为每帧抬/放交替 = 闪）。
                    candidateLift = KeyboardLift.candidateLift(rootBottom, vis.bottom,
                            composerLoc[1], composer.getHeight(), gate.applied(), spacing);
                } else {
                    candidateLift = KeyboardLift.scrollPadFor(keyboard);
                }

                long now = android.os.SystemClock.uptimeMillis();
                int pad = gate.feed(keyboard, shrink, now, candidateLift);
                if (pad < 0) return;                 // -1 = 本次无动作（别碰 UI）
                int lift = composer == null ? 0 : pad;
                if (composer != null) composer.setTranslationY(-lift);
                scroller.setPadding(scroller.getPaddingLeft(), scroller.getPaddingTop(),
                        scroller.getPaddingRight(), pad0 + pad);
                // 现场证据：窗高变化 > 0 ⇒ resize 生效（系统在让位，我们没动手）
                BootLog.say("键盘 页底=" + rootBottom + " 可见底=" + vis.bottom
                        + " 键盘=" + keyboard + " 抬=" + lift + " 补=" + pad
                        + " 窗高变化=" + shrink + " 已可见=" + gate.visibleMs(now) + "ms");
            }
        };

        decor.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        recompute.run();
                    }
                });

        // ---- 触发源必须**完备**：不能只靠"布局事件" ----
        // 真机日志（`审计.md §19.1`）证明这台设备上键盘弹出**不引起窗口 resize**（`窗高变化=0`），
        // 也就是说**可能一次全局布局都不发生**；而"第二次点输入框"时输入框**已经聚焦**，
        // `onFocusChange` 也不会再来。用户原话：「刚进软件可以抬起来，然后收回去又抬不起来了」
        // —— 就是这两条都不来、没人叫醒重算。
        // 所以再加两条**不依赖系统事件**的：① 点输入框本身；② 聚焦期间轮询（150ms，微秒级开销）。
        final View editor = findEditText(composer, 0);
        final boolean[] polling = {false};
        final Runnable poll = new Runnable() {
            @Override
            public void run() {
                recompute.run();
                // 输入框还在、窗口还有焦点 → 继续；否则停（退到后台 / 失焦 / 页面销毁都不空转）
                if (editor != null && editor.isAttachedToWindow() && editor.isFocused()
                        && editor.hasWindowFocus()) {
                    decor.postDelayed(this, POLL_MS);
                } else {
                    polling[0] = false;
                }
            }
        };
        final Runnable kick = new Runnable() {
            @Override
            public void run() {
                recompute.run();                 // 立刻算一次（点下去就有反应）
                if (polling[0]) return;          // 已经在轮询 → 别再起第二条链
                polling[0] = true;
                decor.postDelayed(poll, POLL_MS);
            }
        };
        if (editor != null) {
            // ⚠️ `setOnFocusChangeListener` / `setOnClickListener` 都是**单槽位**的，
            //    这里只在 Kit 建的输入框上挂，本工程没有别处注册它们（grep 过）；
            //    将来若有人要用，得改成"转发式"（保存上一个再串起来）。
            editor.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus) return;
                    kick.run();
                    for (int delay : new int[]{120, 320, 650}) decor.postDelayed(recompute, delay);
                }
            });
            // 点输入框本身 ——「已经聚焦时再点一下」不会触发 onFocusChange，只能靠它。
            editor.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    kick.run();
                }
            });
        }
    }

    /** 在输入行子树里找那个输入框（键盘弹出兜底触发用）；没有就返回 null。 */
    private static View findEditText(View v, int depth) {
        if (v == null || depth > 8) return null;
        if (v instanceof EditText) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findEditText(g.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }
}
