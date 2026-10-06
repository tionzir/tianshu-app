package dev.tianshu.host;

import android.app.Activity;
import android.view.View;

/**
 * 一张会话卡片 —— **会话页与对话页侧栏共用这一份**。
 *
 * 为什么抽出来：这两处原先各写一份（`SessionListActivity.itemCard` 与 `ChatActivity.sessionRow`），
 * 标题字号不一样（`Ui.TITLE` vs 硬编码 14f）、元信息各拼一遍、有没有动作按钮也不同
 * —— 用户反馈「界面有些重复的地方，很突兀」。
 *
 * ## 2026-10-04：改成"一行 = 标题 + 元信息 + ›"
 * 卡片的形态交给 {@link Kit#cardRow}（与其它列表同一套行语言，间距/圆角/箭头都在那儿），
 * 这里只负责"填哪两个字段"和"点了做什么"。
 *
 * ⚠️ **归档 / 删除那两个常驻按钮已收进长按菜单**（`withActions` 不再画按钮）。
 * 依据是参考 App 的结论：「有了（长按）就不必在卡片上再画一个常驻按钮，列表才能干净得像微信」。
 *
 * 这条**是有取舍的** —— 用户 2026-10-03 的真机反馈（#3 / #10）曾经要过"每行一键归档/删除"。
 * 所以把另外两个方案记在这里，要改回去时不必重新想：
 *   - **方案 B**：保留常驻按钮，但做成 12dp 圆角的细线小钮（字号 11、右对齐、与 `›` 同一行），
 *     比原来小一号、不抢标题；
 *   - **方案 C**：卡片上只留一个 `⋯` 钮，点开就是同一个管理面板（兼顾"一键可达"与"列表干净"）。
 *
 * 长按的入口一直都在（{@link Cb#longPress}），两种布局都不影响它。
 */
public final class SessionCard {

    /** 卡片上的动作 —— 调用方决定"点了以后干什么"。 */
    public interface Cb {
        /** 点卡片主体。 */
        void open(SessionList.Item it);

        /** 一键归档（会中止正在跑的会话，调用方自己弹确认）。 */
        void archive(SessionList.Item it);

        /** 一键删除（不可逆，调用方自己弹确认）。 */
        void delete(SessionList.Item it);

        /** 长按（会话页用来出管理面板，侧栏用来出同一份面板）。 */
        void longPress(SessionList.Item it);
    }

    private SessionCard() {
    }

    /**
     * @param withActions 保留参数（历史语义是"要不要画那一排「归档 / 删除」按钮"）。
     *                    现在两种取值都**不画按钮** —— 见类注释；留着它是为了
     *                    "要改回常驻按钮"时有个明确的落点。调用方仍按原样传。
     */
    public static View build(final Activity a, final SessionList.Item it, long now,
                             boolean withActions, final Cb cb) {
        // 一行 = 标题 + 元信息 + `›`，**不带卡片底**。
        //
        // 2026-10-04 改：原先每会话一张独立卡片（`Kit.cardRow`），一屏只放得下五六条 ——
        // 而它现在装在**分组的卡片里**（`Kit.Group`：一段会话 = 一张卡 + 若干行 + 缩进分隔线），
        // 密度翻倍、也更像"列表"而不是"卡片堆"。自己再有底就成了"卡中卡"。
        View row = Kit.menuRow(a, 0,
                it.title == null || it.title.length() == 0 ? "（无标题）" : it.title,
                SessionList.metaLine(it, now),          // 时间 + 状态 + 归档 —— 纯逻辑，两处共用
                cb == null ? null : new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        cb.open(it);
                    }
                });

        if (cb != null) {
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    cb.longPress(it);
                    return true;            // 长按已消费，别再顺手打开这条会话
                }
            });
        }
        return row;
    }
}
