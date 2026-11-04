package net.runelite.client.proxy;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionSet;
import net.runelite.client.plugins.microbot.Microbot;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Configures the JVM to use a SOCKS5 proxy if the appropriate command line argument is provided.
 */
public class ProxyConfiguration {

    private static volatile String username;

    public static String getUsername() {
        return username;
    }

    /**
     * Sets up the proxy configuration based on the provided options.
     * @param options
     * @param proxyInfo
     */
    public static void setupProxy(OptionSet options, ArgumentAcceptingOptionSpec<String> proxyInfo) {
        // ===================== [MICROBOT 本地改动] 代理分项参数 =====================
        // 上游原本只支持 -proxy=<scheme://user:pass@host:port> 这一种形式（走 URI 解析），
        // 且 -proxy-type 属于历史遗留参数，上游见到它就弹窗并 System.exit(1)。
        // 本地新增五个分项参数：-proxy-type / -proxy-host / -proxy-port / -proxy-user / -proxy-pass，
        // 目的：让"密码含 URI 保留字符（@ # : / 等）"的场景也能正确配置代理，
        //       因为分项参数不经过 URI 解析，密码原样传递，无需 URL 编码。
        //
        // 分项模式的调度在 RuneLite.main() 里完成（见那里标注 MICROBOT 本地改动 的位置）：
        // 只要提供了 -proxy-host 就走 setupProxyFromParts()，完全忽略 -proxy 的 URI 形式；
        // 未提供 -proxy-host 时才进入本方法，按上游 URI 方式解析。
        //
        // 合并规则（★ 上游更新后若此处冲突，按此规则覆盖本段 ★）：
        //   1) -proxy-host 存在 -> 分项模式，忽略 -proxy；
        //   2) 否则 -proxy 走上游 URI 解析；此时若同时给了 -proxy-type，
        //      仅当其值为 socks/socks5 时忽略它（视为冗余），否则沿用上游行为：弹窗提示并退出；
        //   3) 两种模式最终都落到 configureProxy() + setupAuthenticator()，行为完全一致。
        //
        // 上游更新后若此处产生冲突，保留本段语义即可，其余上游逻辑不动。
        // ===================== [MICROBOT 本地改动 END] =====================
        if (!options.has(proxyInfo)) {
            // 只给了历史遗留的 -proxy-type 却没有可用的代理地址：沿用上游行为，提示并退出，
            // 避免在用户以为代理已生效的情况下静默直连。
            if (options.has("proxy-type")) {
                Microbot.showMessage("Proxy type is no longer supported, please use the format -proxy=socks://user:pass@host:port or http://user:pass@host:port");
                System.exit(1);
            }
            return;
        }

        URI uri = URI.create(options.valueOf(proxyInfo));

        if (options.has("proxy-type")) {
            // ===================== [MICROBOT 本地改动] =====================
            // 本地新增注册了 -proxy-type 参数，上游这里是无条件退出的。
            // 改为：仅当 -proxy-type 的值不是 socks/socks5 时才拒绝，
            // 从而保持"OSRS 游戏流量必须走 SOCKS"这一安全约束不变。
            // ===================== [MICROBOT 本地改动 END] =====================
            String proxyType = (String) options.valueOf("proxy-type");
            if (proxyType != null && !proxyType.trim().isEmpty()) {
                String normalized = proxyType.trim().toLowerCase(Locale.ROOT);
                if (!normalized.equals("socks") && !normalized.equals("socks5")) {
                    Microbot.showMessage("Proxy type is no longer supported, please use the format -proxy=socks://user:pass@host:port or http://user:pass@host:port");
                    System.exit(1);
                }
            }
        }

        String host = uri.getHost();
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);

        validateProxyScheme(scheme);

        int port = validatePort(uri.getPort());

        String[] credentials = extractCredentials(uri);
        String user = credentials[0];
        String pass = credentials[1];

        configureProxy(host, port);
        username = user;

        if (user != null) {
            setupAuthenticator(user, pass);
        }
    }

    /**
     * ===================== [MICROBOT 本地改动] 代理分项参数 =====================
     * 使用 -proxy-type/-proxy-host/-proxy-port/-proxy-user/-proxy-pass 这组分项参数配置 SOCKS5 代理。
     * 与上游 -proxy=URI 方式的唯一区别：不做 URI 解析，用户名/密码原样使用，
     * 因此密码里含 @ # : / 等字符时无需 URL 编码。
     * @param type  代理类型，socks 或 socks5；为空时默认 socks5
     * @param host  代理主机
     * @param port  代理端口
     * @param user  用户名，可为空
     * @param pass  密码，可为空
     */
    public static void setupProxyFromParts(String type, String host, String port, String user, String pass) {
        String scheme = (type == null || type.trim().isEmpty()) ? "socks5" : type.trim().toLowerCase(Locale.ROOT);

        // 复用上游的 scheme 校验：只接受 socks / socks5，HTTP(S) 代理会被拒绝（保证游戏流量也走代理）
        validateProxyScheme(scheme);

        if (host == null || host.trim().isEmpty()) {
            Microbot.showMessage("Invalid proxy host");
            System.exit(1);
        }

        int portNumber = parsePort(port);

        configureProxy(host.trim(), portNumber);
        username = (user == null || user.isEmpty()) ? null : user;

        if (username != null) {
            setupAuthenticator(username, pass == null ? "" : pass);
        }
    }

    /**
     * ===================== [MICROBOT 本地改动] 代理分项参数 =====================
     * 解析分项参数传入的端口字符串（上游只有基于 URI 的 validatePort(int)）。
     * @param port 端口字符串
     * @return 合法端口
     */
    private static int parsePort(String port) {
        if (port == null || port.trim().isEmpty()) {
            Microbot.showMessage("Invalid proxy port");
            System.exit(1);
        }
        try {
            return validatePort(Integer.parseInt(port.trim()));
        } catch (NumberFormatException e) {
            Microbot.showMessage("Invalid proxy port");
            System.exit(1);
            return -1;
        }
    }
    // ===================== [MICROBOT 本地改动 END] =====================

    /**
     * Validates the proxy scheme to ensure it is SOCKS5.
     * @param scheme
     */
    private static void validateProxyScheme(String scheme) {
        boolean isHttpProxy = scheme.equals("http") || scheme.equals("https");
        if (isHttpProxy) {
            Microbot.showMessage("HTTP(S) proxies are not supported, please use a SOCKS5 proxy. \n\n This is to make sure that osrs traffic is also routed through the proxy.");
            System.exit(1);
        }

        boolean isSocksProxy = scheme.equals("socks") || scheme.equals("socks5");
        if (!isSocksProxy) {
            Microbot.showMessage("Proxy scheme must be socks(5).");
            System.exit(1);
        }
    }

    /**
     * Validates the proxy port to ensure it is a positive integer.
     * @param port
     * @return
     */
    private static int validatePort(int port) {
        if (port <= 0) {
            Microbot.showMessage("Invalid proxy port");
            System.exit(1);
        }
        return port;
    }

    /**
     * Extracts the username and password from the URI's user info.
     * @param uri
     * @return
     */
    private static String[] extractCredentials(URI uri) {
        String user = null;
        String pass = null;
        if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) {
            String[] userInfo = uri.getUserInfo().split(":", 2);
            user = userInfo[0];
            pass = userInfo[1];
        }
        return new String[]{user, pass};
    }

    /**
     * Configures the JVM to use the specified SOCKS5 proxy.
     * @param host
     * @param port
     */
    private static void configureProxy(String host, int port) {
        System.setProperty("socksProxyHost", host);
        System.setProperty("socksProxyPort", String.valueOf(port > 0 ? port : 1080));
    }

    /**
     * Sets up the default authenticator for proxy authentication.
     * @param user
     * @param pass
     */
    private static void setupAuthenticator(String user, String pass) {
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass != null ? pass.toCharArray() : new char[0]);
            }
        });
    }
}