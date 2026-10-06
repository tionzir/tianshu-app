package dev.tianshu.host;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * 星图三页（星图 / 编年史 / 蓝图）共用的装配件 —— 读盘入口 + 几个小控件。
 *
 * 三页的数据来自同一个文件（{@link Constellation#fileFor}），只有呈现不同；
 * 把"读盘 + 小控件"收在一处，免得三页各写一遍再分叉（与 {@link Kit}/{@link AppKit} 同一理由）。
 */
final class StarViews {

    private StarViews() {
    }

    /** 读 App 私有 rootfs 里的 {@code .rivet/constellation.json}；还没有返回 null。 */
    static Constellation.Doc load(Activity a) {
        File rootfs = new RuntimeHost(a).rootfsDir();
        String raw = LocalData.readText(Constellation.fileFor(rootfs));
        if (raw == null) return null;
        return Constellation.parse(raw);
    }

    /** 次要色说明文字（自动换行）。 */
    static TextView muted(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s == null ? "" : s);
        t.setTextSize(Ui.CAPTION);
        t.setLineSpacing(0f, 1.3f);
        Theming.tag(t, Theming.ROLE_MUTED);
        return t;
    }

    /** 正文行（摘要）。 */
    static TextView bodyText(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s == null ? "" : s);
        t.setTextSize(Ui.BODY);
        t.setLineSpacing(0f, 1.3f);
        return t;
    }

    /** 卡片里的一行字段：`标签：值`（值可选中复制、可换行）。 */
    static void field(Activity a, LinearLayout card, String label, String value) {
        TextView t = new TextView(a);
        t.setText(label + "：" + (value == null || value.length() == 0 ? "—" : value));
        t.setTextSize(Ui.CAPTION);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0f, 1.3f);
        t.setPadding(0, Theming.dp(a, Ui.S2), 0, 0);
        Theming.tag(t, Theming.ROLE_MUTED);
        card.addView(t);
    }

    /** 用「、」连起来；空列表返回空串。 */
    static String join(List<String> xs) {
        if (xs == null || xs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    /** 页头右侧的返回圆钮（二级页统一语言，与模型页 / 面板页一致）。 */
    static View backButton(final Activity a) {
        return Kit.roundButton(a, Icon.BACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.finish();
            }
        });
    }
}
