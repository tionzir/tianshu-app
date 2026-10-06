package dev.tianshu.host;

/**
 * 渲染调度（纯逻辑）—— 决定这一帧要重画哪几轮。
 *
 * 为什么要它：流式对话期间**只有最后一轮在长**，前几轮已经收到 `turn_complete`、
 * 内容不会再变。可原先每帧都 `content.removeAllViews()` 把整段对话重建一遍
 * （对话页 250ms 渲染一次）—— 对话越长，每帧要造的 View 越多，主线程被拖住，
 * 表现就是"用着好卡，尤其对话的时候"。
 *
 * 所以把"该从第几轮开始重画"这件事抽成纯逻辑放在这里：HostTest 能直接断言
 * "三十轮时也只动最后一轮"，而不是靠肉眼看着不卡。同 Tabs / StartupText 的做法。
 */
public final class RenderPlan {

    /** 需要整体重画（只发生在轮次变少时，例如重放或清空）。 */
    public static final int FULL = -1;

    private RenderPlan() {
    }

    /**
     * @param rendered 上一帧已经画好的轮数
     * @param total    这一帧解析出来的轮数
     * @return 从第几轮开始重画（它和它之后的都拆掉重造）；{@link #FULL} = 整体重画
     */
    public static int firstDirtyTurn(int rendered, int total) {
        if (rendered < 0 || total < rendered) return FULL;   // 轮次变少：推倒重来才安全
        if (rendered == 0) return 0;                          // 一帧都还没画过
        return rendered - 1;                                  // 只有最后一轮还在长
    }
}
