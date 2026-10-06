package dev.tianshu.host;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 「账号」页 —— 官方**账号 / OAuth** 路由的 App 落点（2026-10-06④ 对账补的）。
 *
 * 此前 App 只有 `/login` 一条：打一发 `POST /account/device` 就把原始 JSON 贴进对话。
 * 但设备码登录是**三步**：拿码 → 给人码和链接 → **轮询** `/account/poll`。
 * 少了第三步，码给了也永远登不上 —— 这页把它补齐。
 *
 * 三块：
 * <ol>
 *   <li>账号状态（`GET /account/status`）—— 星籍 / 主星域 / 称号。</li>
 *   <li>设备码登录 —— 大字号显示 userCode、可点开 verifyUrl、按服务端给的
 *       `pollInterval` 轮询 `/account/poll`，带 `expiresIn` 倒计时与「取消」。</li>
 *   <li>服务商 OAuth（`POST /config/providers/:name/oauth/login|logout`）——
 *       只列 `authType == "oauth"` 的服务商（codex 这类订阅型）；登录态轮
 *       `GET /config/providers` 的 `oauthAuthenticated` 才知道（路由**立即返回 started**，
 *       不是等它返回）。</li>
 * </ol>
 *
 * 入口：设置页「原生入口 → 账号与登录」；命令面板的 `/login`（已改成跳这里）。
 */
public class AccountActivity extends BaseActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout box;
    private LinearLayout loginCard;
    private TextView statusValue;

    /** 当前进行中的设备码登录（null = 没在登）。 */
    private Account.Device device;
    private long deadlineMs;
    private Runnable pollTask;

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
        header.addView(AppKit.pageTitle(this, "账号"));
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));

        View refresh = Kit.headerRefresh(this, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                build();
            }
        });
        header.addView(refresh);

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

        TextView sub = StarViews.muted(this,
                "官方账号接口：星籍状态 / 设备码登录 / 服务商 OAuth —— 登录分三步，最后一步是轮询");
        sub.setPadding(0, Theming.dp(this, Ui.S1), 0, Theming.dp(this, Ui.S3));
        main.addView(sub);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        main.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(main, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Theming.apply(this, root);
        build();
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 渲染

    private void build() {
        if (box == null) return;
        box.removeAllViews();

        // ① 账号状态
        LinearLayout st = Kit.card(this);
        st.addView(AppKit.cardTitle(this, Icon.STAR, "账号状态"));
        statusValue = new TextView(this);
        statusValue.setTextSize(Ui.LABEL);
        Theming.tag(statusValue, Theming.ROLE_ACCENT);
        st.addView(statusValue);
        box.addView(st);

        // ② 登录（设备码）
        loginCard = Kit.card(this);
        box.addView(loginCard);

        // ③ 服务商 OAuth
        LinearLayout oa = Kit.card(this);
        oa.addView(AppKit.cardTitle(this, Icon.SHIELD, "服务商 OAuth"));
        oa.addView(StarViews.muted(this, "订阅型服务商（如 codex）。点「登录」会走浏览器授权。"));
        box.addView(oa);

        loadStatus();
        renderLogin();
        loadOauthProviders(oa);
    }

    private void loadStatus() {
        RuntimeApi.get("/account/status", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                Account.Status s = Account.parseStatus(body);
                if (statusValue != null) {
                    statusValue.setText(s.line());
                    Theming.applyTree(AccountActivity.this, statusValue);
                }
                renderLogin();
            }

            @Override
            public void fail(String message) {
                if (statusValue != null) statusValue.setText("读不到：" + message);
            }
        });
    }

    /** 登录卡按「有没有在登」两种形态画。 */
    private void renderLogin() {
        if (loginCard == null) return;
        loginCard.removeAllViews();
        loginCard.addView(AppKit.cardTitle(this, Icon.COMMAND, "登录"));

        if (device != null && device.ok()) {
            long left = Math.max(0, (deadlineMs - System.currentTimeMillis()) / 1000);
            loginCard.addView(StarViews.muted(this,
                    "在浏览器里打开下面那条链接，输入这串码："));
            TextView code = new TextView(this);
            code.setText(device.userCode);
            code.setTextSize(Ui.TITLE * 1.6f);
            code.setGravity(Gravity.CENTER);
            Theming.tag(code, Theming.ROLE_ACCENT);
            loginCard.addView(code);
            TextView wait = StarViews.muted(this,
                    "等待授权…（约 " + left + " 秒后作废；每 " + device.pollInterval + " 秒查一次）");
            loginCard.addView(wait);

            Button open = new Button(this);
            open.setText("打开授权页");
            Theming.tag(open, Theming.ROLE_CHIP);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openBrowser(device.verifyUrl);
                }
            });
            loginCard.addView(open);

            Button cancel = new Button(this);
            cancel.setText("取消登录");
            Theming.tag(cancel, Theming.ROLE_CHIP);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancelLogin();
                }
            });
            loginCard.addView(cancel);
            return;
        }

        Button start = new Button(this);
        start.setText("设备码登录");
        Theming.tag(start, Theming.ROLE_CHIP);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startLogin();
            }
        });
        loginCard.addView(start);

        Button out = new Button(this);
        out.setText("登出");
        Theming.tag(out, Theming.ROLE_CHIP);
        out.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RuntimeApi.post("/account/logout", "{}", new RuntimeApi.Cb() {
                    @Override
                    public void ok(String body) {
                        status("已登出");
                        loadStatus();
                    }

                    @Override
                    public void fail(String message) {
                        status("登出没成功：" + message);
                    }
                });
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Theming.dp(this, Ui.S1);
        loginCard.addView(out, lp);
        Theming.applyTree(this, loginCard);
    }

    /** 动作回执 —— 就地写进状态行，不弹 toast（免得跟页面对不上）。 */
    private void status(String s) {
        if (statusValue != null) statusValue.setText(s);
    }

    // ---------------------------------------------------------------- 设备码登录（三步）

    private void startLogin() {
        status("正在向服务端要设备码…");
        RuntimeApi.post("/account/device", "{}", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                Account.Device d = Account.parseDevice(body);
                if (d == null || !d.ok()) {
                    status("服务端没给设备码：" + head(body));
                    return;
                }
                device = d;
                deadlineMs = System.currentTimeMillis() + d.expiresIn * 1000L;
                renderLogin();
                openBrowser(d.verifyUrl);            // 直接开浏览器，省一步
                schedulePoll(d.pollInterval * 1000L); // ③ 关键：开始轮询
            }

            @Override
            public void fail(String message) {
                status("要不到设备码：" + message);
            }
        });
    }

    /**
     * 轮询 `/account/poll`。**这一步是原先缺的那一步** —— 没有它，码给了也永远登不上。
     * 停止条件：approved（成功）/ expired（作废）/ 超时（服务端给的 expiresIn）/ 页面销毁。
     */
    private void schedulePoll(final long delayMs) {
        stopPolling();
        pollTask = new Runnable() {
            @Override
            public void run() {
                if (device == null) return;
                if (System.currentTimeMillis() > deadlineMs) {
                    status("授权码已过期，请重新登录");
                    device = null;
                    renderLogin();
                    return;
                }
                RuntimeApi.post("/account/poll", Account.pollBody(device.deviceCode), new RuntimeApi.Cb() {
                    @Override
                    public void ok(String body) {
                        if (Account.isApproved(body)) {
                            status("登录成功");
                            device = null;
                            renderLogin();
                            loadStatus();
                            loadOauthInto();          // 登录态变了，服务商授权列表也重拉
                            return;
                        }
                        if (Account.keepPolling(body)) {
                            renderLogin();            // 刷新倒计时
                            schedulePoll(delayMs);
                            return;
                        }
                        // expired / 认不出的状态：停，如实说
                        status("授权没成：" + Account.pollStatus(body));
                        device = null;
                        renderLogin();
                    }

                    @Override
                    public void fail(String message) {
                        status("轮询失败：" + message);
                        device = null;
                        renderLogin();
                    }
                });
            }
        };
        ui.postDelayed(pollTask, delayMs);
    }

    private void stopPolling() {
        if (pollTask != null) {
            ui.removeCallbacks(pollTask);
            pollTask = null;
        }
    }

    private void cancelLogin() {
        stopPolling();
        device = null;
        RuntimeApi.post("/account/cancel", "{}", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                status("已取消登录");
                renderLogin();
            }

            @Override
            public void fail(String message) {
                status("取消时出错：" + message);
                renderLogin();
            }
        });
    }

    private void openBrowser(String url) {
        if (url == null || url.length() == 0) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            status("打不开浏览器，请手动访问：" + url);
        }
    }

    // ---------------------------------------------------------------- 服务商 OAuth

    private LinearLayout oauthCard;

    private void loadOauthProviders(final LinearLayout card) {
        oauthCard = card;
        RuntimeApi.get("/config/providers", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                renderOauth(body);
            }

            @Override
            public void fail(String message) {
                card.addView(StarViews.muted(AccountActivity.this, "读不到服务商：" + message));
            }
        });
    }

    /** 登录成功后只刷新服务商那一块（整页重建会闪）。 */
    private void loadOauthInto() {
        if (oauthCard == null) return;
        while (oauthCard.getChildCount() > 3) oauthCard.removeViewAt(3);
        loadOauthProviders(oauthCard);
    }

    private void renderOauth(String providersJson) {
        if (oauthCard == null) return;
        while (oauthCard.getChildCount() > 3) oauthCard.removeViewAt(3);

        List<Account.Oauth> list = Account.oauthProviders(providersJson);
        if (list.isEmpty()) {
            oauthCard.addView(StarViews.muted(this,
                    "没有 OAuth 型服务商（当前配的都是「填密钥」那种）。"));
            Theming.applyTree(this, oauthCard);
            return;
        }
        for (final Account.Oauth p : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView t = new TextView(this);
            t.setText(p.label + "  ·  " + p.state());
            t.setTextSize(Ui.CAPTION);
            Theming.tag(t, Theming.ROLE_MUTED);
            row.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button b = new Button(this);
            b.setText(p.action());
            Theming.tag(b, Theming.ROLE_CHIP);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    oauthAction(p, !p.authenticated);
                }
            });
            row.addView(b);
            oauthCard.addView(row);
        }
        Theming.applyTree(this, oauthCard);
    }

    private void oauthAction(final Account.Oauth p, final boolean login) {
        status((login ? "已发起 " : "已退出 ") + p.label + " 的授权");
        RuntimeApi.post(Account.oauthPath(p.name, login), "{}", new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                // 路由**立即返回 started** —— 登录态要轮 /config/providers 才知道，不是等它返回。
                if (login) pollOauth(p.name, 0);
                else loadOauthInto();
            }

            @Override
            public void fail(String message) {
                status(p.label + " 授权没成功：" + message);
            }
        });
    }

    /** 轮询服务商授权态：最多 {@link #OAUTH_POLL_MAX} 次，每次隔 2 秒。 */
    private static final int OAUTH_POLL_MAX = 30;

    private void pollOauth(final String name, final int round) {
        if (round >= OAUTH_POLL_MAX) {
            status("等 " + name + " 授权超时 —— 授权页里完成后再点一次刷新。");
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                RuntimeApi.get("/config/providers", new RuntimeApi.Cb() {
                    @Override
                    public void ok(String body) {
                        for (Account.Oauth p : Account.oauthProviders(body)) {
                            if (p.name.equals(name) && p.authenticated) {
                                status(name + " 已授权");
                                loadOauthInto();
                                return;
                            }
                        }
                        pollOauth(name, round + 1);
                    }

                    @Override
                    public void fail(String message) {
                        status("查授权态失败：" + message);
                    }
                });
            }
        }, 2000);
    }

    private static String head(String s) {
        if (s == null) return "(null)";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
