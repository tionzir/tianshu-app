package dev.tianshu.host;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 自绘图标 —— **真图形，不是字符**。
 *
 * ## 为什么不再用符号当图标
 * 在此之前，全 App 的图标都是"拿一个 Unicode 符号当字打印出来"（`☰` 菜单、`☥` 星域、
 * `⚡` 缓存、`◧` 上下文…）。这么做的理由当时是成立的：项目**只依赖 android.jar**，
 * 既不引图标库也不加图片资源，而符号不占体积。代价有三个，前两个在真机上已经露头：
 *
 *   1. **字形覆盖是设备相关的**。334 个系统字体里，`☰`/`☥`/`◧` 只有 **1 个**字体覆盖
 *      （`⏵` 更是 **0 个**，真机直接是豆腐块）。
 *      换个 ROM、换个字体集，这些图标就可能集体消失成方框。
 *   2. **符号的画法不受控**。同一个 `☰`，不同字体的粗细/比例/基线全不一样，字号一改
 *      还会跟着文字基线漂移；而图标本该是**几何**，不是字。
 *   3. **换肤通不了**。图标本应跟文字一个色（`Theming` 的角色色），但符号是"字"，
 *      只能整体当正文色，圆钮上的主色图标、错误色的叉号都做不到。
 *
 * 所以这一版把图标全部改成**自绘**：纯 `Path`/`Canvas` 画几何图形，零资源、零依赖，
 * 与 `OrbitSpinner`、`SplashBackdrop` 是同一种做法。
 *
 * ## 颜色怎么来
 * 图标**不在** `Theming` 的"按角色贴色"体系里（它只认 TextView/Button）。
 * 但图标必须跟文字同色 —— 尤其圆钮上的主色图标、错误色的叉号。
 * 所以 {@link Theming#walk} 里加了一条分支：遇到 {@link Icon} 就按它的**角色**（`Theming.tag`）
 * 取色调 {@link #setColor}。**别在调用处硬给颜色** —— 那样换肤就断了，
 * 与 `Kit` 那条"控件只声明角色、贴色归 Theming"的纪律是同一回事。
 *
 * ## 坐标约定
 * 每个图标都在 **24×24 的逻辑坐标**里画，`onDraw` 时按实际尺寸整体缩放 ——
 * 这样路径能照着参考线写，不必随尺寸重算。
 */
public class Icon extends View {

    /** 设计坐标系（逻辑单位）。 */
    private static final float UNIT = 24f;

    // ══════════════════════════════ 图标种类 ══════════════════════════════

    /** 菜单（三条横线）。 */
    public static final int MENU = 1;
    /** 新建（加号）。 */
    public static final int PLUS = 2;
    /** 命令（终端提示符 `>_`）。 */
    public static final int COMMAND = 3;
    /** 发送（上箭头）。 */
    public static final int SEND = 4;
    /** 关闭（叉）。 */
    public static final int CLOSE = 5;
    /** 返回（左箭头）。 */
    public static final int BACK = 6;
    /** 进入（右尖角 `›`）。 */
    public static final int CHEVRON = 7;
    /** 展开（下尖角）。 */
    public static final int CHEVRON_DOWN = 8;
    /** 收起（上尖角）。 */
    public static final int CHEVRON_UP = 9;
    /** 勾选。 */
    public static final int CHECK = 10;
    /** 星（对话页签 / 模型入口）。 */
    public static final int STAR = 11;
    /** 齿轮（设置 / 思考强度）。 */
    public static final int GEAR = 12;
    /** 明暗（外观页签，半填充圆）。 */
    public static final int HALF = 13;
    /** 上下文占用（仪表盘）。 */
    public static final int GAUGE = 14;
    /** 闪电（缓存命中）。 */
    public static final int BOLT = 15;
    /** 时钟（计价时段）。 */
    public static final int CLOCK = 16;
    /** 盾牌（审批模式）。 */
    public static final int SHIELD = 17;
    /** 星域徽记（品牌 / 助手头像）。 */
    public static final int BRAND = 18;
    /** 心跳线（运行状态）。 */
    public static final int PULSE = 19;
    /** 小圆点（列表前缀 / 时间分割）。 */
    public static final int DOT = 20;
    /** 复制。 */
    public static final int COPY = 21;
    /** 刷新（环形箭头）。 */
    public static final int REFRESH = 22;
    /** 搜索（放大镜）。 */
    public static final int SEARCH = 23;
    /** 用户（头像 / 我）。 */
    public static final int PERSON = 24;
    /** 文档（日志 / 记录）。 */
    public static final int DOC = 25;
    /** 强度条（三根递增竖条）—— 推理档位那枚 chip 用。 */
    public static final int LEVEL = 26;
    /** 代码仓库（主干两个提交点 + 一条汇入的分支）—— 个人页的「官方仓库」卡用。 */
    public static final int REPO = 27;

    /** 全部种类 —— 供自检用（见 HostTest 的图标覆盖断言）。 */
    public static final int[] KINDS = {
            MENU, PLUS, COMMAND, SEND, CLOSE, BACK, CHEVRON, CHEVRON_DOWN, CHEVRON_UP,
            CHECK, STAR, GEAR, HALF, GAUGE, BOLT, CLOCK, SHIELD, BRAND, PULSE, DOT,
            COPY, REFRESH, SEARCH, PERSON, DOC, LEVEL, REPO,
    };

    private final int kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    private int color = 0xFF000000;
    private int sizeDp = 24;
    /** 线宽（逻辑单位）。几个"细"图标会临时调小，画完还原。 */
    private float strokeUnits = 2f;

    public Icon(Context c, int kind) {
        super(c);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        solid.setStyle(Paint.Style.FILL);
        applyColor();
    }

    /** 尺寸（dp）；默认 24。 */
    public Icon size(int dp) {
        this.sizeDp = dp;
        requestLayout();
        return this;
    }

    /**
     * 换色 —— **由 {@link Theming#walk} 按角色调**，不要在调用处硬给。
     */
    public void setColor(int argb) {
        if (color != argb) {
            color = argb;
            applyColor();
            invalidate();
        }
    }

    public int kind() {
        return kind;
    }

    private void applyColor() {
        stroke.setColor(color);
        solid.setColor(color);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int want = Math.round(getResources().getDisplayMetrics().density * sizeDp);
        setMeasuredDimension(resolveSize(want, widthMeasureSpec), resolveSize(want, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float s = Math.min(w, h) / UNIT;
        canvas.save();
        canvas.translate((w - UNIT * s) / 2f, (h - UNIT * s) / 2f);
        canvas.scale(s, s);
        stroke.setStrokeWidth(strokeUnits);
        drawIcon(canvas);
        canvas.restore();
    }

    // ══════════════════════════════ 画 ══════════════════════════════

    private void line(Canvas c, float x0, float y0, float x1, float y1) {
        c.drawLine(x0, y0, x1, y1, stroke);
    }

    private void drawIcon(Canvas c) {
        switch (kind) {
            case MENU:
                line(c, 3.5f, 7, 20.5f, 7);
                line(c, 3.5f, 12, 20.5f, 12);
                line(c, 3.5f, 17, 20.5f, 17);
                break;

            case PLUS:
                line(c, 12, 4.5f, 12, 19.5f);
                line(c, 4.5f, 12, 19.5f, 12);
                break;

            case COMMAND:
                // 终端提示符：一个 `>` 加一条下划线
                line(c, 4.5f, 7, 10.5f, 12);
                line(c, 10.5f, 12, 4.5f, 17);
                line(c, 13, 18.5f, 20, 18.5f);
                break;

            case SEND:
                line(c, 12, 20, 12, 4.5f);
                line(c, 12, 4.5f, 6.5f, 10);
                line(c, 12, 4.5f, 17.5f, 10);
                break;

            case CLOSE:
                line(c, 6.5f, 6.5f, 17.5f, 17.5f);
                line(c, 17.5f, 6.5f, 6.5f, 17.5f);
                break;

            case BACK:
                line(c, 19, 12, 5, 12);
                line(c, 5, 12, 11, 6);
                line(c, 5, 12, 11, 18);
                break;

            case CHEVRON:
                line(c, 9.5f, 5, 16.5f, 12);
                line(c, 16.5f, 12, 9.5f, 19);
                break;

            case CHEVRON_DOWN:
                line(c, 5, 9.5f, 12, 16.5f);
                line(c, 12, 16.5f, 19, 9.5f);
                break;

            case CHEVRON_UP:
                line(c, 5, 14.5f, 12, 7.5f);
                line(c, 12, 7.5f, 19, 14.5f);
                break;

            case CHECK:
                line(c, 4.5f, 12.5f, 9.5f, 18);
                line(c, 9.5f, 18, 19.5f, 6);
                break;

            case STAR:
                path.reset();
                path.moveTo(12, 1.5f);
                path.quadTo(13.6f, 10.4f, 22.5f, 12);
                path.quadTo(13.6f, 13.6f, 12, 22.5f);
                path.quadTo(10.4f, 13.6f, 1.5f, 12);
                path.quadTo(10.4f, 10.4f, 12, 1.5f);
                path.close();
                c.drawPath(path, solid);
                break;

            case GEAR:
                // 齿轮。旧画法是**小圆 + 8 根细放射线** —— 那个形状就是**太阳**，不是齿轮
                // （用户 2026-10-06：「底部功能页的图标太阳改成齿轮」）。
                // 改法的关键在**齿**：太阳的射线又细又**悬空**；齿轮的齿必须**粗**、
                // 且**接在环上**，再加一圈**轴孔**，轮廓才读得成"齿轮"。
                c.drawCircle(12, 12, 2.6f, stroke);       // 轴孔
                c.drawCircle(12, 12, 7.2f, stroke);       // 环：连续一整圈 —— 与太阳最大的区别
                float ringW = strokeUnits;
                stroke.setStrokeWidth(3.2f);              // 齿要粗；细了又变回太阳
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float cx = (float) Math.cos(a);
                    float cy = (float) Math.sin(a);
                    c.drawLine(12 + cx * 7.2f, 12 + cy * 7.2f,
                               12 + cx * 10.2f, 12 + cy * 10.2f, stroke);
                }
                stroke.setStrokeWidth(ringW);             // 还原（onDraw 每帧也会重设，这里不越界）
                break;

            case HALF:
                c.drawCircle(12, 12, 9, stroke);
                path.reset();
                oval.set(3, 3, 21, 21);
                path.addArc(oval, -90, 180);
                path.close();
                c.drawPath(path, solid);
                break;

            case GAUGE:
                // 仪表盘：外圈留一个缺口 + 一根指针
                oval.set(3.5f, 3.5f, 20.5f, 20.5f);
                c.drawArc(oval, 140, 260, false, stroke);
                line(c, 12, 12, 16.5f, 7.5f);
                c.drawCircle(12, 12, 1.6f, solid);
                break;

            case BOLT:
                path.reset();
                path.moveTo(13.5f, 2);
                path.lineTo(4.5f, 13.5f);
                path.lineTo(10.5f, 13.5f);
                path.lineTo(9.5f, 22);
                path.lineTo(19.5f, 10);
                path.lineTo(13.5f, 10);
                path.close();
                c.drawPath(path, solid);
                break;

            case CLOCK:
                c.drawCircle(12, 12, 8.8f, stroke);
                line(c, 12, 6.6f, 12, 12);
                line(c, 12, 12, 16, 14);
                break;

            case SHIELD:
                path.reset();
                path.moveTo(12, 2.2f);
                path.lineTo(20, 5.4f);
                path.lineTo(20, 12);
                path.quadTo(20, 18, 12, 21.8f);
                path.quadTo(4, 18, 4, 12);
                path.lineTo(4, 5.4f);
                path.close();
                c.drawPath(path, stroke);
                break;

            case BRAND:
                // 星域徽记：一个环 + 一竖一横（天枢的记号）
                oval.set(8, 2, 16, 10);
                c.drawOval(oval, stroke);
                line(c, 12, 10, 12, 21.5f);
                line(c, 6.5f, 14, 17.5f, 14);
                break;

            case PULSE:
                path.reset();
                path.moveTo(2, 12);
                path.lineTo(7.5f, 12);
                path.lineTo(10, 5.5f);
                path.lineTo(14, 18.5f);
                path.lineTo(16.5f, 12);
                path.lineTo(22, 12);
                c.drawPath(path, stroke);
                break;

            case DOT:
                c.drawCircle(12, 12, 3, solid);
                break;

            case COPY:
                c.drawRect(4, 4, 15.5f, 15.5f, stroke);
                c.drawRect(8.5f, 8.5f, 20, 20, stroke);
                break;

            case REFRESH:
                // 2026-10-06 重画。旧画法在真机上读不出「刷新」（用户原话：「看起来不太像」）。
                // 像素取证（浅色主题 1260×2800，见 `审计.md §22`）：整个图形外接只有 **42×46 px**，
                // 却落在一个 **122 px** 的圆钮里（图形只占画布直径的 62%）；缺口 ~110°、
                // 箭头是两笔正交细线 —— 缩到那个尺寸后读起来是「一个带小缺口的细圆环 + 右上角一个小直角」，
                // 不像循环箭头。
                //
                // 三处改动，各对一条已量到的症状：
                //   ① 半径 7.5 → 8.8 单位：图形不再缩在画布中央一小团（直径占比 62% → 73%）；
                //   ② 缺口 110° → 125°：**一眼看得出环是断的**，不再像字母 O / C；
                //   ③ 箭头由两笔细线改成**实心三角**：细线在这个尺寸会被抗锯齿糊掉，实心块不会。
                oval.set(3.2f, 3.2f, 20.8f, 20.8f);          // 半径 8.8，中心仍是 (12,12)
                c.drawArc(oval, 105, 235, false, stroke);    // 从正下方顺时针到右上，缺口开在右侧
                // 箭头贴在弧的终点（340° 方向），尖端沿切线朝右下 —— 与弧的走向同向
                path.reset();
                path.moveTo(21.6f, 12.8f);                   // 尖
                path.lineTo(17.8f, 9.9f);                    // 底边一端
                path.lineTo(22.7f, 8.1f);                    // 底边另一端
                path.close();
                c.drawPath(path, solid);
                break;

            case SEARCH:
                c.drawCircle(10.5f, 10.5f, 6, stroke);
                line(c, 15, 15, 20.5f, 20.5f);
                break;

            case PERSON:
                c.drawCircle(12, 8.5f, 4, stroke);
                oval.set(4.5f, 14, 19.5f, 25);
                c.drawArc(oval, 180, 180, false, stroke);
                break;

            case DOC:
                path.reset();
                path.moveTo(5.5f, 3);
                path.lineTo(14, 3);
                path.lineTo(18.5f, 7.5f);
                path.lineTo(18.5f, 21);
                path.lineTo(5.5f, 21);
                path.close();
                c.drawPath(path, stroke);
                line(c, 8.5f, 11.5f, 15.5f, 11.5f);
                line(c, 8.5f, 15.5f, 15.5f, 15.5f);
                break;

            case LEVEL:
                // 强度条：三根逐渐升高的竖条 —— 推理档位的高低一眼可辨
                line(c, 6, 17.5f, 6, 13f);
                line(c, 12, 17.5f, 12, 9.5f);
                line(c, 18, 17.5f, 18, 6f);
                break;

            case REPO:
                // 代码仓库：主干两个提交点 + 一条从左侧汇入的分支。
                // 仍然是自绘（不引图标库、不用字符）—— 与其余图标同一套纪律，见类注释。
                c.drawCircle(12, 5.6f, 2.2f, stroke);      // 主干上端的提交点
                c.drawCircle(12, 18.4f, 2.2f, stroke);     // 主干下端的提交点
                c.drawCircle(6.4f, 12f, 2.2f, stroke);     // 分支上的提交点
                line(c, 12, 7.8f, 12, 16.2f);              // 主干竖线
                path.reset();
                path.moveTo(8.6f, 12f);                    // 从分支点右缘出发
                path.quadTo(12f, 12f, 12f, 9.2f);          // 平滑拐进主干
                c.drawPath(path, stroke);
                break;

            default:
                // 未知种类：画一个空圆，别静默什么都不画（那样很难查）
                c.drawCircle(12, 12, 8.5f, stroke);
                break;
        }
    }
}
