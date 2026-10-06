package dev.tianshu.host;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板页 —— 终端里 `panels` 那一面命令的 App 落点（`/tasks` `/mcp` `/plugin` `/cache`
 * `/mission` `/storage` `/worktrees` `/schedule` `/scratch` `/tools` `/trust` …）。
 *
 * 全是**只读**路由：这一版不给写操作。理由不是偷懒 —— 这一面里能"改"的动作
 * （停子代理、装卸插件、重启 MCP）在真机上一次误触的代价，比多一个按钮值钱；
 * 先把"看得见"做扎实。
 *
 * 渲染用 {@link ConsoleSection}（与设置页同一个组件），目录在 {@link PanelCatalog}。
 */
public class PanelsActivity extends BaseActivity {

    private final List<ConsoleSection> sections = new ArrayList<ConsoleSection>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        int pad = Theming.dp(this, Ui.S6);
        main.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        header.addView(AppKit.pageTitle(this, "面板"));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        View refresh = Kit.headerRefresh(this, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reloadAll();
            }
        });
        header.addView(refresh);

        // 返回口与模型页统一成**圆钮**（`‹` U+2039，实测 74 个字体覆盖）—— 二级页本该一套语言。
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Theming.dp(this, Ui.S1);
        header.addView(Kit.roundButton(this, Icon.BACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), blp);
        main.addView(header);

        TextView sub = new TextView(this);
        sub.setText("后台任务、外部工具、插件、缓存与存储占用 —— 运行中的细节都在这儿");
        sub.setTextSize(Ui.CAPTION);
        sub.setLineSpacing(0f, 1.3f);
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S3));
        Theming.tag(sub, Theming.ROLE_MUTED);
        main.addView(sub);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (PanelCatalog.Panel p : PanelCatalog.PANELS) {
            ConsoleSection s = new ConsoleSection(this, p.title, p.hint, p.path, p.depth);
            sections.add(s);
            box.addView(s.view());
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        main.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(main, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Theming.apply(this, root);

        reloadAll();
    }

    private void reloadAll() {
        for (ConsoleSection s : sections) s.load();
    }
}
