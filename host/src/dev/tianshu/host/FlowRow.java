package dev.tianshu.host;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 会**自动换行**的一行（流式布局）。
 *
 * ⚠️ **当前没有使用者**（2026-10-04）：状态 chips 那一排最后改成了**横滑** ——
 * 用户依次要求「一排都要全部显示」「不要删减（要完整的 deepseek-v4-flash）」「不要太挤」，
 * 而这三条在 360~400dp 屏上**无法同时满足**（最紧的压法也要 288dp，那一版正是他说"太挤"的），
 * 于是只能横滑（见 `ChatActivity.onCreate` 里那段"为什么是横滑"）。
 * 本类**保留**：它是个正经控件、换行算法也在纯逻辑侧（`StatusBar.wrapRows`，有 `testChipWrap` 断言）；
 * **但别再拿它去装那排 chips**。
 *
 * （当初不用 `HorizontalScrollView` 的理由是用户说过「要全部显示出来，不用滑动」——
 *   后来他的要求变了：宁可滑也不能折行/缩水。）
 *
 * 为什么不用 `HorizontalScrollView`：用户反馈很明确 —— 输入框上面那一排
 * 「要全部显示出来，不用滑动」。横滑容器还有个副作用：它会跟子 View 抢短按
 * （项目里踩过：chip 点不动）。
 *
 * 为什么不用现成的 FlowLayout：它在 androidx / support 里，而本项目**只依赖 android.jar**
 * （构建链是手搓的 aapt2 → javac → D8，没有依赖仓库）。所以自己量、自己摆。
 *
 * 换行算法本身在 {@link StatusBar#wrapRows}（纯逻辑、有断言）—— 这里只负责把 View 摆上去。
 */
public final class FlowRow extends ViewGroup {

    private final int gap;

    public FlowRow(Context c, int gapPx) {
        super(c);
        this.gap = gapPx;
    }

    /** 可见子 View 的下标（GONE 的不参与换行计算，否则行数会算多）。 */
    private int[] visibleIndexes() {
        List<Integer> keep = new ArrayList<Integer>();
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i).getVisibility() != View.GONE) keep.add(i);
        }
        int[] out = new int[keep.size()];
        for (int i = 0; i < out.length; i++) out[i] = keep.get(i);
        return out;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = width - getPaddingLeft() - getPaddingRight();
        if (available <= 0) available = width;

        int[] vis = visibleIndexes();
        int[] widths = new int[vis.length];
        int[] heights = new int[vis.length];
        for (int i = 0; i < vis.length; i++) {
            View c = getChildAt(vis[i]);
            measureChild(c, widthMeasureSpec, heightMeasureSpec);
            widths[i] = c.getMeasuredWidth();
            heights[i] = c.getMeasuredHeight();
        }

        int[] rows = StatusBar.wrapRows(widths, available, gap);
        int totalH = getPaddingTop() + getPaddingBottom();
        int idx = 0;
        for (int r = 0; r < rows.length; r++) {
            int rowH = 0;
            for (int k = 0; k < rows[r] && idx < heights.length; k++, idx++) {
                if (heights[idx] > rowH) rowH = heights[idx];
            }
            totalH += rowH + (r > 0 ? gap : 0);
        }

        int desiredW = getPaddingLeft() + getPaddingRight() + available;
        setMeasuredDimension(resolveSize(desiredW, widthMeasureSpec),
                resolveSize(totalH, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int available = (r - l) - getPaddingLeft() - getPaddingRight();
        int[] vis = visibleIndexes();
        int[] widths = new int[vis.length];
        int[] heights = new int[vis.length];
        for (int i = 0; i < vis.length; i++) {
            View c = getChildAt(vis[i]);
            widths[i] = c.getMeasuredWidth();
            heights[i] = c.getMeasuredHeight();
        }
        int[] rows = StatusBar.wrapRows(widths, available, gap);

        int y = getPaddingTop();
        int idx = 0;
        for (int ri = 0; ri < rows.length; ri++) {
            int x = getPaddingLeft();
            int rowH = 0;
            for (int k = 0; k < rows[ri] && idx < vis.length; k++, idx++) {
                View c = getChildAt(vis[idx]);
                c.layout(x, y, x + widths[idx], y + heights[idx]);
                x += widths[idx] + gap;
                if (heights[idx] > rowH) rowH = heights[idx];
            }
            y += rowH + gap;
        }
    }
}
