package dev.tianshu.host;

/**
 * 「配置密钥」那条路的**命令行拼装**（纯逻辑 —— HostTest 直接断言，不必起 Android）。
 *
 * 用户选的 provider 与粘贴进来的 key 都会进 shell 命令行，而 key 里出现 `'`、`$`、
 * 空格都是合法的（各家服务商的 token 形态不一）。所以这里只做一件事：
 * **把值安全地塞进单引号里**。判据是"拼出来的命令行不会因为值的内容而断开或展开" ——
 * 这条由 HostTest 用几个真实的刁钻取值钉住。
 *
 * 为什么值不值得用更花哨的转义：天枢只在 rootfs 里跑自己的 CLI，命令是固定的两条
 * （{@code config set-key} / {@code config set-default}），唯一可变的只有参数。
 * 单引号包裹 + 内部单引号拆成 {@code '\''} 是 POSIX 下完备的做法，够用且好验证。
 */
public final class SetupScript {

    /** POSIX 单引号安全包裹：内部的 `'` 拆成 `'\''`，其余原样。 */
    public static String quote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** 设某个 provider 的 API key（harness 自己负责加密落盘，App 不碰密钥格式）。 */
    public static String setKey(String provider, String key) {
        return "rivet config set-key " + quote(provider) + " " + quote(key);
    }

    /** 把该 provider 设为默认。 */
    public static String setDefault(String provider) {
        return "rivet config set-default " + quote(provider);
    }

    /** 一条完整的配置命令：先设 key，成功再把默认切过去。 */
    public static String configureProvider(String provider, String key) {
        return setKey(provider, key) + " && " + setDefault(provider);
    }

    private SetupScript() {
    }
}
