package dev.tianshu.gateprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Gate 探针 v2 —— 在真机 app 沙箱里验证三条否决性门槛。
 *
 * v2 相比 v1 的改动（v1 的文本没法选中复制，用户只能念第一行）：
 *   - 顶部一行紧凑判定，一眼可读：GATE1=... GATE3=...
 *   - 正文可选中（setTextIsSelectable）
 *   - 加「复制全部」按钮，一键进剪贴板
 */
public class MainActivity extends Activity {

    private final StringBuilder log = new StringBuilder();
    /** run() 里记录最近一次退出码 */
    private int lastExit = Integer.MIN_VALUE;
    private String lastExc = null;

    private String gate1Verdict = "?";
    private String gate3Verdict = "?";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        probe();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        // 顶部紧凑判定 —— 一眼可读，便于口述
        final TextView head = new TextView(this);
        head.setTextSize(18f);
        head.setPadding(0, 0, 0, 16);
        head.setText("GATE1=" + gate1Verdict + "   GATE3=" + gate3Verdict);

        // 一键复制
        Button copy = new Button(this);
        copy.setText("复制全部到剪贴板");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("gate-probe", head.getText() + "\n\n" + log.toString()));
                }
            }
        });

        TextView body = new TextView(this);
        body.setTextSize(11f);
        body.setTextIsSelectable(true);   // v1 缺这个，导致没法选中
        body.setText(log.toString());

        ScrollView sv = new ScrollView(this);
        sv.addView(body);

        root.addView(head);
        root.addView(copy);
        root.addView(sv);
        setContentView(root);
    }

    private void probe() {
        header();
        String g1 = gate1_nativeLibraryDir();
        gate2_status();
        String g3 = gate3_deps();
        gate1Verdict = g1;
        gate3Verdict = g3;
    }

    // ---------------------------------------------------------------- 信息头
    private void header() {
        p("=== 天枢 Gate 探针 ===");
        p("设备: " + Build.MANUFACTURER + " " + Build.MODEL);
        p("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        p("ABI: " + Arrays.toString(Build.SUPPORTED_ABIS));
        p("nativeLibraryDir: " + getApplicationInfo().nativeLibraryDir);
        p("dataDir: " + getApplicationInfo().dataDir);
        p("");
    }

    // ---------------------------------------------------------------- ① exec
    /** @return 紧凑判定，如 PASS / FAIL(exit=126) / FAIL(exc=EACCES) */
    private String gate1_nativeLibraryDir() {
        p("=== ① exec 门槛：从 nativeLibraryDir 执行 ELF ===");
        File dir = new File(getApplicationInfo().nativeLibraryDir);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            p("  ✗ nativeLibraryDir 为空");
            p("");
            return "FAIL(no-libs)";
        }
        for (File f : files) {
            p("  发现: " + f.getName() + "  " + f.length() + " B  canExec=" + f.canExecute());
        }
        File lib = new File(dir, "libproot.so");
        if (!lib.exists()) {
            lib = files[0];
            p("  （未找到 libproot.so，改用 " + lib.getName() + " 验证 exec 能力）");
        }

        p("  --- 直跑 ---");
        p(run(Arrays.asList(lib.getAbsolutePath(), "--version"), null));
        int direct = lastExit;
        String directExc = lastExc;

        p("  --- 带 LD_LIBRARY_PATH=nativeLibraryDir 再跑 ---");
        p(run(Arrays.asList(lib.getAbsolutePath(), "--version"), dir.getAbsolutePath()));
        int withLd = lastExit;

        p("  --- 对照组：副本放 dataDir 再执行（预期失败）---");
        int copyExit = Integer.MIN_VALUE;
        String copyExc = null;
        try {
            File copyFile = new File(getFilesDir(), "probe-copy.bin");
            copyFile(lib, copyFile);
            copyFile.setExecutable(true, false);
            p("  副本: " + copyFile.getAbsolutePath() + " canExec=" + copyFile.canExecute());
            p(run(Arrays.asList(copyFile.getAbsolutePath(), "--version"), null));
            copyExit = lastExit;
            copyExc = lastExc;
        } catch (Throwable t) {
            p("  副本尝试异常: " + t);
        }
        p("");

        // 判定：以「直跑」为准，LD_LIBRARY_PATH 只影响依赖解析
        if (directExc != null && direct != 0) {
            return "FAIL(exit=" + direct + ",exc=" + directExc + ")";
        }
        if (direct == 0) {
            return "PASS(direct=0,ld=" + (withLd == 0 ? "0" : String.valueOf(withLd)) + ")";
        }
        if (withLd == 0) {
            return "PASS(needLD)";
        }
        return "FAIL(direct=" + direct + ",ld=" + withLd + ")";
    }

    // ---------------------------------------------------------------- ② ptrace
    private void gate2_status() {
        p("=== ② ptrace 门槛：本 App 能否 ptrace 自己的子进程 ===");
        p("  yama ptrace_scope: " + readFile("/proc/sys/kernel/yama/ptrace_scope"));
        p("  本进程 TracerPid: " + grepProcStatus("TracerPid"));
        p("  （判定依赖 ① 的结果：proot 能起来 = ptrace 可用）");
        p("");
    }

    // ---------------------------------------------------------------- ③ 依赖
    private String gate3_deps() {
        p("=== ③ 依赖门槛：proot 的动态库 ===");
        File dir = new File(getApplicationInfo().nativeLibraryDir);
        String[] need = {"libtalloc.so.2", "libandroid-shmem.so", "libproot.so"};
        int ok = 0;
        for (String n : need) {
            File f = new File(dir, n);
            if (f.exists()) {
                ok++;
                p("  ✓ " + n + " (" + f.length() + " B)");
            } else {
                p("  ✗ " + n + " 缺失");
            }
        }
        p("");
        return ok + "/" + need.length;
    }

    // ---------------------------------------------------------------- 工具
    private String run(List<String> cmd, String ldPath) {
        lastExit = Integer.MIN_VALUE;
        lastExc = null;
        StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (ldPath != null) {
                Map<String, String> env = pb.environment();
                env.put("LD_LIBRARY_PATH", ldPath);
            }
            Process proc = pb.start();
            InputStream is = proc.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines < 6) {
                sb.append("      ").append(line).append('\n');
                lines++;
            }
            lastExit = proc.waitFor();
            sb.insert(0, "    退出码 = " + lastExit + "\n");
        } catch (Throwable t) {
            lastExc = t.getClass().getSimpleName();
            sb.append("    异常 = ").append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private String readFile(String path) {
        try {
            BufferedReader br = new BufferedReader(new java.io.FileReader(path));
            String s = br.readLine();
            br.close();
            return s == null ? "(空)" : s;
        } catch (Throwable t) {
            return "(不可读: " + t.getMessage() + ")";
        }
    }

    private String grepProcStatus(String key) {
        try {
            BufferedReader br = new BufferedReader(new java.io.FileReader("/proc/self/status"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(key)) { br.close(); return line; }
            }
            br.close();
        } catch (Throwable t) { /* ignore */ }
        return "(未找到)";
    }

    private void p(String s) { log.append(s).append('\n'); }
}
