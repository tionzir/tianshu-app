package dev.tianshu.host;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令面板 —— 把天枢的 104 条 `/` 命令摆成可点可查的目录。
 *
 * 这是**硬不变量 C** 在界面上的落点：信息架构不是拍脑袋想的，是从命令面推导出来的 ——
 * 分组直接来自 `data/components.json` 的 6 个落点面（顶栏 / 输入区 / 消息流内动作 /
 * 面板页 / 设置页 / 自动行为），外加显式登记的「移动端不适用」5 条（各带理由）。
 *
 * 三件事刻意不做：
 *   1. 不发明新按钮 —— 面板里出现的每一条都来自命令注册表（HostTest 会拿真实数据验）；
 *   2. 不假装能执行没有 API 的命令 —— 点按只做「复制」，要发给天枢走长按（预填进对话输入框）；
 *   3. 不把「不适用」藏起来 —— 5 条终端交互产物单独一节列出，并写明为什么不适用。
 *
 * 数据由 build.sh 打进 `assets/tianshu-cmd/`（源在 `data/`，不复制仓库里那份）。
 */
public class CommandPanelActivity extends BaseActivity {

    private TextView statusLine;

    @Override
    protected void onResume() {
        super.onResume();
        // 从「外观」页返回时本页是复用回来的（不会重走 onCreate）—— 重贴一遍
        Theming.refresh(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 与四个主页面同一套页边距（原先是写死的 18）—— 这一页不带底栏，padding 放 root 无妨
        int pad = Theming.dp(this, Ui.S6);
        root.setPadding(pad, pad, pad, 0);

        // 页头：与其它页一样以 DISPLAY 标题起头（原先这页直接就是一行状态，进来不知道在哪）
        root.addView(AppKit.pageTitle(this, "命令面板"));

        statusLine = new TextView(this);
        statusLine.setTextSize(Ui.CAPTION);
        statusLine.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S1));
        statusLine.setText("载入命令目录…");
        Theming.tag(statusLine, Theming.ROLE_MUTED);

        TextView hint = new TextView(this);
        hint.setTextSize(Ui.CAPTION);
        Theming.tag(hint, Theming.ROLE_MUTED);
        hint.setPadding(0, 0, 0, Theming.dp(this, Ui.S3));
        // 2026-10-04 深查：这句提示与实现**正好相反** —— 点一下是"填进对话输入框"（既定设计，
        // 见 749b59e「命令面板点击改'发到对话'」），长按才是复制。提示写反了，按实现改。 
        hint.setText("点一下填进对话输入框 · 长按复制");

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(body);

        root.addView(statusLine);
        root.addView(hint);
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Theming.apply(this, root);

        build(body, readAsset("tianshu-cmd/commands.txt"),
                readAsset("tianshu-cmd/components.json"));
    }

    // ---------------------------------------------------------------- 渲染
    private void build(LinearLayout body, String commandsText, String componentsJson) {
        CommandCatalog.Catalog cat = CommandCatalog.load(commandsText, componentsJson);

        if (cat.commands.isEmpty()) {
            status("命令列表读不到 —— 安装包可能不完整");
            body.addView(AppKit.error(this, "命令目录读不到",
                    "安装包里的命令表缺失，重装一次 App 通常就好了。", null));
            return;
        }

        StringBuilder head = new StringBuilder();
        head.append("共 ").append(cat.coverage.commandCount).append(" 条命令 · 按落点分 ")
                .append(cat.surfaces.size()).append(" 组");
        if (cat.coverage.notApplicableCount > 0) {
            head.append(" · 其中 ").append(cat.coverage.notApplicableCount).append(" 条手机上用不到");
        }
        status(head.toString());
        // 「覆盖 GREEN / RED」是给自己看的自检指标，不该出现在用户面前；
        // 只有数据真出问题时才提一句
        if (!cat.coverage.ok) {
            status("命令表与界面落点对不上 —— 可能是版本不一致");
        }

        // 6 个已知落点面按语义顺序排；出现未知 surface 就补在末尾（不静默丢）
        List<CommandCatalog.Surface> ordered = new ArrayList<CommandCatalog.Surface>();
        List<String> known = CommandCatalog.surfaceKeys();
        for (String key : known) {
            for (CommandCatalog.Surface s : cat.surfaces) {
                if (s.key.equals(key)) ordered.add(s);
            }
        }
        for (CommandCatalog.Surface s : cat.surfaces) {
            if (!known.contains(s.key)) ordered.add(s);
        }

        // 每个落点面 = 一个分组（组标题 + 一张卡 + 缩进分隔线），行语言与全 App 一致。
        for (CommandCatalog.Surface s : ordered) {
            String zh = CommandCatalog.surfaceZh(s.key);
            Kit.Group g = Kit.group(this, zh + "  ·  " + s.items.size() + " 条");
            for (String cmd : s.items) {
                g.row(commandRow(cmd, cat.descOf(cmd)), 16);
            }
            body.addView(g.root);
        }

        // 真机反馈 #7：这些命令在 App 里**确实用不了**（终端进程内能力 / 触屏无对应语义），
        // 默认折叠起来，不占版面，想看再展开。
        // **不删数据**：命令名仍留在路由表里，敲出来照样被识别并给出说明 —— 删行会让
        // CommandRouting.of() 返 null，而 App 对未知命令的处理是「当普通消息发出去」，
        // 反而会把 /branch 这类重新掉进对话（当初 /connect 那个 bug 的成因）。
        final Kit.Group naGroup = Kit.group(this, null);
        if (cat.notApplicable.isEmpty()) {
            naGroup.body.addView(note("（无）"));
        } else {
            for (Map.Entry<String, String> e : cat.notApplicable.entrySet()) {
                naGroup.row(commandRow(e.getKey(), e.getValue()), 16);
            }
        }
        naGroup.root.setVisibility(View.GONE);

        final int naCount = cat.notApplicable.size();
        final boolean[] naOpen = {false};
        final android.widget.Button naToggle = new android.widget.Button(this);
        naToggle.setText("手机上用不到的命令（" + naCount + " 条）");
        Theming.tag(naToggle, Theming.ROLE_GHOST);
        naToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                naOpen[0] = !naOpen[0];
                naGroup.root.setVisibility(naOpen[0] ? View.VISIBLE : View.GONE);
                naToggle.setText("手机上用不到的命令（" + naCount + " 条）"
                        + (naOpen[0] ? "（收起）" : "（展开）"));
            }
        });
        body.addView(naToggle);
        body.addView(naGroup.root);
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        Theming.tag(t, Theming.ROLE_MUTED);
        t.setPadding(24, 8, 24, 8);
        return t;
    }

    /**
     * 一行命令：命令名 + 说明 + 落点状态，走 {@link Kit#menuRow} 的扩展版。
     *
     * 2026-10-04 改：原先是自造的三行控件（浅框 + 粗体命令名 + 说明 + 状态行），
     * 与全 App 的行语言不一路。现在统一到 {@link Kit}，按压反馈也一并有了
     * （原先这一页**完全没有按压** —— 点下去没有任何回应，用户自然觉得"点不动"）。
     */
    private View commandRow(final String cmd, String desc) {
        View v = Kit.menuRow(this, 0, cmd,
                desc == null || desc.length() == 0 ? "（无说明）" : desc,
                fateLine(cmd),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent i = new Intent(CommandPanelActivity.this, ChatActivity.class);
                        i.putExtra(ChatActivity.EXTRA_PREFILL, cmd + " ");
                        startActivity(i);
                    }
                });
        // 复制降为长按 —— 与「点按 = 发到对话」的主手势错开（原注释里的取舍，保持不变）
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("tianshu-cmd", cmd));
                toast("已复制 " + cmd);
                return true;
            }
        });
        return v;
    }

    // ---------------------------------------------------------------- 小工具

    /**
     * 那一行"这条命令在 App 里到底有没有用"。
     *
     * 三类分开标（见 {@link CommandFate}）：有原生界面的给落点、终端产物直说用不了、
     * 其余说明"发出去只是给天枢的一句话"。以前 104 条长得一模一样 —— 用户看不出哪条有效。
     */
    static TextView fateLine(android.content.Context ctx, String cmd) {
        CommandFate.Kind kind = CommandFate.of(cmd);
        TextView t = new TextView(ctx);
        // 第三轮改：原先前缀是 `✓` / `✕` / `·` 三个符号 —— 它们是"靠字体碰运气"的那类字符
        //改成一个词，语义更直白；"可用 / 不可用"仍由**颜色**区分（ROLE_OK / WARN）。
        t.setText(kind == CommandFate.Kind.NATIVE
                ? "可用 · " + CommandFate.where(cmd)
                : kind == CommandFate.Kind.TERMINAL
                        ? "不可用 · " + CommandFate.where(cmd)
                        : "发出去只是给天枢的一句话");
        t.setTextSize(11f);
        t.setPadding(0, 6, 0, 0);
        Theming.tag(t, kind == CommandFate.Kind.NATIVE ? Theming.ROLE_OK
                : kind == CommandFate.Kind.TERMINAL ? Theming.ROLE_WARN : Theming.ROLE_MUTED);
        return t;
    }

    private TextView fateLine(String cmd) {
        return fateLine(this, cmd);
    }

    private String readAsset(String path) {
        try {
            InputStream in = getAssets().open(path);
            try {
                return ChatActivity.readAll(in);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private void status(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusLine.setText(s);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
