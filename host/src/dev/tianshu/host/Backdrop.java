package dev.tianshu.host;

/**
 * 背景绘制的高度判据 —— 纯逻辑，不碰 android 类，HostTest 直接断言
 *（同 {@link KeyboardLift} 的做法：要被断言的逻辑不许挂在 View 上）。
 *
 * ## 为什么背景要"按固定全屏高"画
 * 背景三层挂在 `android.R.id.content` 上（见 {@link Theming#refresh}）。软键盘弹出时若窗口
 * **resize**（`adjustResize`），容器会变矮 —— 若绘制按容器高度来：
 *   - 图片（`BitmapDrawable` FILL）会被**纵向压扁**；
 *   - 渐变（`GradientDrawable`）会被**压缩**，色带位置随之变化。
 * 两种观感在用户那里都是同一句话：「背景图片会抬起来 / 变了」。
 *
 * 所以绘制高度取 `max(全屏高, 容器高)`：容器变矮时仍按全屏画（超出部分被裁掉，位置不变），
 * 容器比全屏还高时按容器画（不留白底）。**这样"背景不动"与"键盘怎么让位"解耦** ——
 * 无论系统是 resize 还是我们自己抬输入框，背景都不动。
 */
public final class Backdrop {

    private Backdrop() {
    }

    /**
     * 背景层该按多高绘制（px）。
     *
     * @param fullHeight   全屏高（{@code displayMetrics.heightPixels}）
     * @param boundsHeight 当前容器高
     */
    public static int extentFor(int fullHeight, int boundsHeight) {
        int full = fullHeight > 0 ? fullHeight : 0;
        int bounds = boundsHeight > 0 ? boundsHeight : 0;
        return Math.max(full, bounds);
    }
}
