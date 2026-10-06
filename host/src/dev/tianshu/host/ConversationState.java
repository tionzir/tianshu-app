package dev.tianshu.host;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 SSE 事件累积成界面要的状态。纯逻辑、不碰 Android，所以能拿真实报文当测试基准。
 *
 * 事件清单与用途（见 `SseParser`）。
 */
public final class ConversationState {

    /** 回答正文 —— `text_delta` 增量拼接。 */
    public final StringBuilder answer = new StringBuilder();

    /** 思考过程 —— `thinking_delta` 增量拼接（可选择展示）。 */
    public final StringBuilder thinking = new StringBuilder();

    /** 出现过的 phase，按顺序（状态条用）。 */
    public final List<String> phases = new ArrayList<String>();

    /** 用户发出去的那句（服务端会回显成 `user` 事件）。 */
    public String userText;

    /** 会话状态（`status` 事件）。 */
    public String status;

    /** 收尾状态（`done` 事件）：completed / interrupted / … */
    public String doneStatus;

    /** 最后见到的事件序号。 */
    public int lastSeq = -1;

    /** `replay_window` 给出的重放水位 —— 重连时用来补历史。 */
    public int floorSeq = -1;
    public int diskFirstSeq = -1;
    public int diskLastSeq = -1;

    /** 最近一个 `done` 的 seq（-1 = 还没收到过）。 */
    public int lastDoneSeq = -1;

    /**
     * 最近一个 `done` 之后**又来了内容**（= 有新一轮正在跑）。
     *
     * 为什么需要它：回放一条老会话时，服务端会把**每一轮历史**都发一遍，
     * 于是流里躺着 **N 个 `done`**。而读流循环原先一看到 `done` 就 break
     * ——老会话只渲染出第 1 轮（2026-10-05 真机复现：会话有 14 轮，App 只显示 1 轮，
     * 发消息后视图又跳到第 2 轮，用户看到的是"记录不在了 + 消息对不上"）。
     */
    public boolean streamingAfterDone;

    /** 收到过的事件总数（不含被丢弃的心跳）。 */
    public int eventCount;

    /** 一轮是否已被 `turn_complete` 标记结束。 */
    public boolean turnComplete;

    /**
     * 这轮是不是已经彻底结束（收到过 `done`）。
     *
     * 为什么必须有这个判断：SSE 是**长连接**，服务端发完 `done` 并不会关连接
     * （之后还在发 `: ping` 心跳）。所以「读流」这件事不能靠 readLine 返回 null 来收尾，
     * 必须在 done 到达时主动退出 —— 否则界面会永远停在「流式中」，
     * 而且 busy 标志卡住，第二句话都发不出去。
     */
    public boolean isFinished() {
        return doneStatus != null;
    }

    /**
     * 本次回放是否**截掉了更早的历史**。
     *
     * `/stream` 只回放内存环里的事件；服务端在流开头发一条 `replay_window` 元事件，
     * 给出 `diskFirstSeq`（磁盘上第一条）与 `floorSeq`（本窗口第一条）。
     * `floorSeq > diskFirstSeq` ⇒ 前面还有事件没送来 —— 界面必须**说出来**，
     * 否则用户看到的就是"老会话少了半截记录"却毫无提示（2026-10-05 真机反馈）。
     *
     * 补历史的冷通道是 `GET /sessions/:id/events?before=<floorSeq>`（分页回填）；
     * 本版只做"如实告知"，分页回填留待后续。
     */
    public boolean hasEarlierHistory() {
        return diskFirstSeq >= 0 && floorSeq > diskFirstSeq;
    }

    /**
     * **回放读完了吗** —— 读流循环只能在它为 true 时收流。
     *
     * 判据：已消费到回放窗口的末端（`lastSeq >= diskLastSeq`）**且**没有正在跑的轮
     * （最近一个 `done` 之后没有新内容）。拿不到 `replay_window` 时退回旧行为
     * （第一个 `done` 就算完）——宁可退化成老样子，也不要在这里把流挂住。
     */
    public boolean replayFinished() {
        if (diskLastSeq < 0) return true;
        return lastSeq >= diskLastSeq && !streamingAfterDone;
    }

    public void apply(String event, String data) {
        eventCount++;

        Integer seq = MiniJson.num(data, "seq");
        // 取**最大**而不是直接赋值：续聊走的增量流在开头会发 replay_window /
        // job_snapshot，这两条的 seq 是 0。直接赋值会把水位打回 0，
        // 于是下一次续聊又变成 since=0 的全量重放（历史事件与本页已有事件叠加 → 轮次翻倍）。
        // 实测 seq 跨 run 单调递增（第一轮 1..22，第二轮 23..38），所以取 max 安全。
        if (seq != null && seq.intValue() > lastSeq) lastSeq = seq.intValue();

        if ("text_delta".equals(event)) {
            String t = MiniJson.str(data, "text");
            if (t != null) answer.append(t);

        } else if ("thinking_delta".equals(event)) {
            String t = MiniJson.str(data, "text");
            if (t != null) thinking.append(t);

        } else if ("user".equals(event)) {
            String t = MiniJson.str(data, "text");
            if (t != null) userText = t;

        } else if ("phase".equals(event)) {
            String p = MiniJson.str(data, "phase");
            if (p != null) phases.add(p);

        } else if ("status".equals(event)) {
            String s = MiniJson.str(data, "status");
            if (s != null) status = s;

        } else if ("done".equals(event)) {
            doneStatus = MiniJson.str(data, "status");
            lastDoneSeq = seq == null ? -1 : seq.intValue();
            streamingAfterDone = false;

        } else if ("turn_complete".equals(event)) {
            turnComplete = true;

        } else if ("replay_window".equals(event)) {
            Integer f = MiniJson.num(data, "floorSeq");
            if (f != null) floorSeq = f.intValue();
            Integer df = MiniJson.num(data, "diskFirstSeq");
            if (df != null) diskFirstSeq = df.intValue();
            Integer dl = MiniJson.num(data, "diskLastSeq");
            if (dl != null) diskLastSeq = dl.intValue();
        }

        // 合成的 seq=0 元事件（replay_window / job_snapshot / zen_phase）不算"有内容在跑"。
        // 只认**真正的内容事件**：status / phase / context_budget / hook_result 这些
        // 在回放尾部也会出现，认了它们会永远收不了流。
        boolean synthetic = seq == null || seq.intValue() == 0;
        boolean content = "user".equals(event) || "user_question".equals(event)
                || "text_delta".equals(event) || "thinking_delta".equals(event)
                || "tool_use".equals(event) || "tool_result".equals(event);
        if (content && !synthetic) streamingAfterDone = true;
    }
}
