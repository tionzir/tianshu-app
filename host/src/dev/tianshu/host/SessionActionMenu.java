package dev.tianshu.host;

import android.app.Activity;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话管理动作面板（改名 / 归档 / 取消归档 / 删除）—— 会话列表页与对话页侧拉菜单共用一份。
 *
 * 为什么单独抽出来：「删除到底打哪条路径」有一处反直觉映射（裸 `DELETE /sessions/:id`
 * 是**归档**，`/permanent` 才是删除，见 {@link SessionActions}）。两个界面各抄一份的话，
 * 迟早有一份会接错 —— 用户以为删干净了，其实只是归档、磁盘还占着。收在一处，坏也只坏一处。
 */
public final class SessionActionMenu {

    /** 动作回执（成功或失败的人话）。调用方自己决定显示在哪、以及要不要刷新列表。 */
    public interface OnDone {
        void done(String message);
    }

    /** 「会话详情」这一项的标识 —— 不是 SessionActions 的服务端动作，只是个"开页面"的占位。 */
    private static final String DETAIL = "__detail__";

    private SessionActionMenu() {
    }

    /** 打开会话详情页（官方会话级接口的落点）。 */
    static void openDetail(Activity a, SessionList.Item it) {
        Intent i = new Intent(a, SessionDetailActivity.class);
        i.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, it.id);
        a.startActivity(i);
    }

    /** 弹操作面板。`onDone` 在动作返回后于**主线程**调用。 */
    public static void open(final Activity a, final SessionList.Item it, final OnDone onDone) {
        final String label = (it.title == null || it.title.length() == 0) ? it.id : it.title;

        final List<String> acts = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        // 第一项是「会话详情」—— 官方**会话级**接口（待批准 / 改动文件 / 后台任务 / 运行挂钩 /
        // 审查门）的入口；那些东西对话里看不见（2026-10-06② 顺着 3.28.0 的 302 条路由对账补的）。
        // 它**不走** SessionActions（那是服务端动作），只开页面。
        labels.add("会话详情");
        acts.add(DETAIL);
        labels.add("改名");
        acts.add(SessionActions.RENAME);
        labels.add(it.archived ? "取消归档" : "归档");
        acts.add(it.archived ? SessionActions.UNARCHIVE : SessionActions.ARCHIVE);
        labels.add("删除");
        acts.add(SessionActions.DELETE);

        ActionSheet.show(a, label, labels.toArray(new String[labels.size()]),
                new ActionSheet.OnPick() {
                    @Override
                    public void pick(int index) {
                        String action = acts.get(index);
                        if (DETAIL.equals(action)) {
                            openDetail(a, it);
                            return;
                        }
                        if (SessionActions.RENAME.equals(action)) askRename(a, it, label, onDone);
                        else if (SessionActions.DELETE.equals(action)) confirmDelete(a, it, label, onDone);
                        else if (SessionActions.ARCHIVE.equals(action)) confirmArchive(a, it, label, onDone);
                        else call(a, SessionActions.UNARCHIVE, it, null, onDone);
                    }
                });
    }

    /** 列表行上的「一键归档」：与面板里那条共用同一套确认（会**中止正在跑的会话**）。 */
    public static void archive(final Activity a, final SessionList.Item it, final OnDone onDone) {
        confirmArchive(a, it, labelOf(it), onDone);
    }

    /** 列表行上的「一键删除」：与面板里那条共用同一套确认（**不可恢复**）。 */
    public static void delete(final Activity a, final SessionList.Item it, final OnDone onDone) {
        confirmDelete(a, it, labelOf(it), onDone);
    }

    /** 「取消归档」：非破坏性、可逆，不再二次确认。 */
    public static void unarchive(final Activity a, final SessionList.Item it, final OnDone onDone) {
        call(a, SessionActions.UNARCHIVE, it, null, onDone);
    }

    private static String labelOf(SessionList.Item it) {
        return (it.title == null || it.title.length() == 0) ? it.id : it.title;
    }

    /** 改名：先弹输入框补标题，再发 PATCH。 */
    private static void askRename(final Activity a, final SessionList.Item it, String current,
                                  final OnDone onDone) {
        ActionSheet.promptText(a, "改名", current, "会话标题", new ActionSheet.OnSubmit() {
            @Override
            public void submit(String text) {
                call(a, SessionActions.RENAME, it, text, onDone);
            }
        });
    }

    /** 删除不可逆 —— 二次确认，把后果写清楚。 */
    private static void confirmDelete(final Activity a, final SessionList.Item it, String label,
                                      final OnDone onDone) {
        ActionSheet.show(a, "永久删除「" + label + "」？\n对话记录会被真正抹掉，不可恢复。",
                new String[]{"确认删除"}, new ActionSheet.OnPick() {
                    @Override
                    public void pick(int index) {
                        call(a, SessionActions.DELETE, it, null, onDone);
                    }
                });
    }

    /** 归档也要确认：它会**中止正在跑的会话**（Wave 0 实测：status 直接变 aborted）。 */
    private static void confirmArchive(final Activity a, final SessionList.Item it, String label,
                                       final OnDone onDone) {
        ActionSheet.show(a, "归档「" + label + "」？\n会从默认列表收起（可恢复）；正在跑的话会被中止。",
                new String[]{"确认归档"}, new ActionSheet.OnPick() {
                    @Override
                    public void pick(int index) {
                        call(a, SessionActions.ARCHIVE, it, null, onDone);
                    }
                });
    }

    /** 发请求。方法/路径一律走 {@link SessionActions}，避免这里把「删除」接到软删上。 */
    private static void call(Activity a, String action, final SessionList.Item it,
                             final String title, final OnDone onDone) {
        final String path = SessionActions.pathFor(action, it.id);
        final String act = action;
        final OnDone done = onDone;
        RuntimeApi.Cb cb = new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                if (a.isFinishing() || a.isDestroyed()) return;   // 页面没了就别再碰它的视图树
                if (done != null) done.done(okText(act) + " —— 已刷新");
            }

            @Override
            public void fail(String message) {
                // RuntimeApi 已把服务端那句人话带出来（"HTTP 409：…"），照原样显示
                if (a.isFinishing() || a.isDestroyed()) return;
                if (done != null) done.done(message);
            }
        };
        if (SessionActions.RENAME.equals(action)) {
            RuntimeApi.patch(path, SessionActions.renameBody(title), cb);
        } else if (SessionActions.DELETE.equals(action)) {
            // 未归档的会话必须先归档 —— 服务端 `/permanent` 只对已归档生效（否则 409，
            // 用户看到的就是"删不掉"）。几步走是用户不需要知道的实现细节。
            callPlan(SessionActions.deletePlan(it.archived), 0, it, cb);
        } else if (SessionActions.ARCHIVE.equals(action)) {
            RuntimeApi.delete(path, cb);
        } else {
            RuntimeApi.post(path, "{}", cb);
        }
    }

    /** 顺序打一串动作（目前只有「删除」用得上：先归档、再永久删）。失败即停，原因原样回执。 */
    private static void callPlan(final List<String> plan, final int at,
                                 final SessionList.Item it, final RuntimeApi.Cb finalCb) {
        callPlan(plan, at, it, finalCb, true);
    }

    /** 可重试步骤失败后等这么久再试一次 —— 归档是**异步中止**，需要一点时间落地。 */
    private static final int PLAN_RETRY_MS = 900;

    private static void callPlan(final List<String> plan, final int at,
                                 final SessionList.Item it, final RuntimeApi.Cb finalCb,
                                 final boolean retryAllowed) {
        if (at >= plan.size()) return;
        // 重试策略是纯逻辑（SessionActions.retryableStep），这里只负责"隔一会儿再打一次"
        final boolean mayRetry = retryAllowed && SessionActions.retryableStep(plan.get(at));
        final String action = plan.get(at);
        RuntimeApi.delete(SessionActions.pathFor(action, it.id), new RuntimeApi.Cb() {
            @Override
            public void ok(String body) {
                if (at + 1 < plan.size()) callPlan(plan, at + 1, it, finalCb, true);
                else finalCb.ok(body);
            }

            @Override
            public void fail(String message) {
                if (mayRetry) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            new Runnable() {
                                @Override
                                public void run() {
                                    callPlan(plan, at, it, finalCb, false);
                                }
                            }, PLAN_RETRY_MS);
                } else {
                    finalCb.fail(message);
                }
            }
        });
    }

    private static String okText(String action) {
        if (SessionActions.UNARCHIVE.equals(action)) return "已取消归档";
        if (SessionActions.ARCHIVE.equals(action)) return "已归档";
        if (SessionActions.DELETE.equals(action)) return "已删除";
        return "已改名";
    }
}
