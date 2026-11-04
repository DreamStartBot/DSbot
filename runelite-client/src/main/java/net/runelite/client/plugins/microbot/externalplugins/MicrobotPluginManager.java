/*
 * Copyright (c) 2023 Microbot
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.microbot.externalplugins;

import com.fasterxml.jackson.databind.annotation.NoClass;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.MutableGraph;
import com.google.common.io.Files;
import com.google.common.reflect.ClassPath;
import com.google.gson.Gson;
import com.google.inject.Binder;
import com.google.inject.CreationException;
import com.google.inject.Injector;
import com.google.inject.Module;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.microbot.MicrobotApi;
import net.runelite.client.plugins.microbot.recovery.StartupRecovery;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.ui.SplashScreen;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class MicrobotPluginManager {
    private static final File PLUGIN_DIR = new File(RuneLite.RUNELITE_DIR, "microbot-plugins");
    // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
    // 运行时副本目录：用副本加载脚本 jar，让原 jar 复制完就能立刻释放。
    //
    // 为什么要副本：URLClassLoader 打开 jar 时会一直持有文件句柄，
    // 在 Windows 上原 jar 会被锁住，没法覆盖 —— 调试时反复替换脚本 jar 很不方便。
    // 改成把原 jar 复制到这个目录再用副本加载，原 jar 复制完就没人占着了。
    //
    // 副本在脚本卸载时删除，客户端退出时清理整个目录。
    //
    // 冲突时如何覆盖：保留这个常量。
    // ===================== [MICROBOT 本地改动 END] =====================
    private static final File RUNTIME_PLUGIN_DIR = new File(RuneLite.RUNELITE_DIR, "microbot-plugins-runtime");
    private static final String INSTALLED_VERSION_GROUP = "microbotPluginVersions";
    private static final String INSTALLED_VERSION_KEY_PREFIX = "plugin.";
    private static final String UPDATE_NOTIFICATION_GROUP = "microbotPluginUpdateNotifications";
    private static final String UPDATE_NOTIFICATION_KEY_PREFIX = "plugin.";

    private final OkHttpClient okHttpClient;
    private final MicrobotPluginClient microbotPluginClient;
    private final EventBus eventBus;
    private final ScheduledExecutorService executor;
    private final PluginManager pluginManager;
    private final Gson gson;
    private final ConfigManager configManager;
    private final MicrobotApi microbotApi;

    private final Map<String, URLClassLoader> loaders = new ConcurrentHashMap<>();

    // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
    // 每个脚本对应的运行时副本文件。
    //
    // 为什么要副本：URLClassLoader 打开 jar 时会一直持有文件句柄，
    // Windows 上原 jar 会被锁住，覆盖不了。改成复制一份到运行时目录再加载，
    // 原 jar 复制完就没人占着了。
    //
    // 冲突时如何覆盖：保留这个字段。
    // ===================== [MICROBOT 本地改动 END] =====================
    private final Map<String, File> runtimeCopies = new ConcurrentHashMap<>();

    private final Map<String, String> blockedPlugins = new ConcurrentHashMap<>();

    private final Map<String, String> notifiedBlockedPlugins = new HashMap<>();

    @Inject
    @Named("safeMode")
    private boolean safeMode;

    private final Map<String, MicrobotPluginManifest> manifestMap = new ConcurrentHashMap<>();

    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private volatile boolean profileRefreshInProgress = false;
    private static final String PLUGIN_PACKAGE = "net.runelite.client.plugins.microbot";

    @Inject
    private MicrobotPluginManager(
            OkHttpClient okHttpClient,
            MicrobotPluginClient microbotPluginClient,
            EventBus eventBus,
            ScheduledExecutorService executor,
            PluginManager pluginManager,
            Gson gson,
            ConfigManager configManager,
            MicrobotApi microbotApi
    ) {
        this.okHttpClient = okHttpClient;
        this.microbotPluginClient = microbotPluginClient;
        this.eventBus = eventBus;
        this.executor = executor;
        this.pluginManager = pluginManager;
        this.gson = gson;
        this.configManager = configManager;
        this.microbotApi = microbotApi;

        PLUGIN_DIR.mkdirs();
    }

    /**
     * Initializes the MicrobotPluginManager
     */
    public void init() {
        loadManifest();
    }

    /**
     * Loads the plugin manifest list from the remote server and updates the local manifest map.
     * If the manifest has changed, posts an ExternalPluginsChanged event.
     */
    private void loadManifest() {
        try {
            List<MicrobotPluginManifest> manifests = microbotPluginClient.downloadManifest();
            Map<String, MicrobotPluginManifest> next = new HashMap<>(manifests.size());

            com.google.gson.JsonArray allReleases = null;
            try {
                allReleases = microbotPluginClient.fetchAllReleases();
                log.debug("Fetched {} releases from GitHub", allReleases.size());
            } catch (IOException ex) {
                log.warn("Failed to fetch GitHub releases: {}", ex.getMessage());
                log.debug("Releases fetch error", ex);
            }

            for (MicrobotPluginManifest m : manifests) {
                next.put(m.getInternalName(), m);
                if (allReleases != null) {
                    try {
                        List<String> versions = microbotPluginClient.parseVersionsFromReleases(m, allReleases);
                        m.setAvailableVersions(versions);
                    } catch (IOException ex) {
                        log.warn("Failed to parse available versions for {}: {}", m.getInternalName(), ex.getMessage());
                        log.debug("Version parse error", ex);
                    }
                }
            }
            boolean changed = !next.keySet().equals(manifestMap.keySet())
                    || next.entrySet().stream().anyMatch(e -> {
                MicrobotPluginManifest cur = manifestMap.get(e.getKey());
                return cur == null || !Objects.equals(cur.getSha256(), e.getValue().getSha256());
            });
            if (changed) {
                manifestMap.clear();
                manifestMap.putAll(next);
                log.info("Loaded {} plugin manifests.", manifestMap.size());
                eventBus.post(new ExternalPluginsChanged());
            } else {
                log.debug("Plugin manifests unchanged ({} entries), skipping event.", manifestMap.size());
            }
        } catch (Exception e) {
            log.error("Failed to fetch plugin manifests", e);
        }
    }

    public Map<String, MicrobotPluginManifest> getManifestMap() {
        return Collections.unmodifiableMap(manifestMap);
    }

    public Optional<MicrobotPluginManifest> findManifest(String name) {
        return manifestMap.values().stream()
                .filter(manifest -> name.equalsIgnoreCase(manifest.getInternalName())
                        || name.equalsIgnoreCase(manifest.getDisplayName()))
                .findFirst();
    }

    public boolean isLoaded(String internalName) {
        return getInstalledPlugins().stream()
                .anyMatch(plugin -> plugin.getClass().getSimpleName().equalsIgnoreCase(internalName));
    }

    public boolean isLoadedFromHub(String internalName) {
        URLClassLoader loader = loaders.get(internalName);
        return loader != null && getInstalledPlugins().stream()
                .anyMatch(plugin -> plugin.getClass().getSimpleName().equalsIgnoreCase(internalName)
                        && plugin.getClass().getClassLoader() == loader);
    }

    /**
     * Gets the File object for the plugin JAR file corresponding to the given internal name.
     *
     * @param internalName the internal name of the plugin
     * @return the File object representing the plugin JAR
     */
    private File getPluginJarFile(String internalName) {
        return new File(PLUGIN_DIR, internalName + ".jar");
    }

    /**
     * Gets the plugin manifest for a given plugin instance.
     *
     * @param internalName
     * @return
     */
    private byte[] getPluginJarByteArray(String internalName) {
        return new File(PLUGIN_DIR, internalName + ".jar").getPath().getBytes();
    }

    /**
     * Creates an OkHttpClient instance that does not use any proxy settings.
     *
     * @param base
     * @return
     */
    private static OkHttpClient noProxy(OkHttpClient base) {
        return base.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .proxySelector(ProxySelector.of(null))
                .build();
    }

    /**
     * Verifies that the SHA-256 hash of a locally installed plugin matches the
     * authoritative hash from the manifest map.
     * <p>
     * This ensures the integrity of the plugin and detects tampering or corruption.
     *
     * @param internalName the internal name of the plugin to verify (must not be null or empty)
     * @return {@code true} if the plugin exists in both the local and authoritative manifests
     * and the SHA-256 hashes match, {@code false} otherwise
     * @throws IllegalArgumentException if {@code internalName} is null or empty
     */
    private boolean verifyHash(String internalName) {
        if (internalName == null || internalName.isEmpty()) {
            throw new IllegalArgumentException("Internal name is null/empty");
        }

        MicrobotPluginManifest authoritativeManifest = manifestMap.get(internalName);
        InstalledPluginVersion storedVersion = lookupInstalledPluginVersion(internalName).orElse(null);

        String localHash = calculateHash(internalName);
        String authoritativeHash = storedVersion != null ? storedVersion.getSha256()
                : authoritativeManifest != null ? authoritativeManifest.getSha256() : null;

        if (Strings.isNullOrEmpty(localHash) || Strings.isNullOrEmpty(authoritativeHash)) {
            return false;
        }

        var result = localHash.equals(authoritativeHash);

        if (!result) {
            log.warn("Hash mismatch for plugin {}: local={}, authoritative={}",
                    internalName, localHash, authoritativeHash);
        }
        return result;
    }

    /**
     * Calculates the SHA-256 hash of the given JAR data.
     *
     * @param interalName
     * @return
     */
    private String calculateHash(String interalName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            File file = getPluginJarFile(interalName);
            try (InputStream inputStream = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("Error calculating plugin hash", e);
            return "";
        }
    }

    public static File[] createSideloadingFolder() {
        try {
            Files.createParentDirs(PLUGIN_DIR);

            if (!PLUGIN_DIR.exists() && PLUGIN_DIR.mkdir()) {
                log.debug("Directory for sideloading was created successfully.");
            }
        } catch (IOException e) {
            log.trace("Error creating directory for microbot-plugins!", e);
        }

        return PLUGIN_DIR.listFiles();
    }

    /**
     * Loads a single plugin from the sideload folder if not already loaded.
     */
    private void loadSideLoadPlugin(String internalName) {
        File pluginFile = getPluginJarFile(internalName);
        if (!pluginFile.exists()) {
            log.debug("Plugin file {} does not exist", pluginFile);
            return;
        }
        // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
        // 上游原本在这里拿「已加载插件的类名集合」判断这个 jar 加载过没有，
        // 同样依赖「jar 文件名 == 插件类名」的假设，一个 jar 装多个脚本时会判断错。
        // 改成查 loaders：它的 key 就是 jar 的 internalName，是正确的判断依据。
        //
        // 冲突时如何覆盖：保留这一行。
        // ===================== [MICROBOT 本地改动 END] =====================
        if (loaders.containsKey(internalName)) {
            return;
        }
        try {
            if (!verifyHash(internalName)) {
                log.warn("Plugin hash verification failed for: {}", internalName);
            }
            List<Class<?>> plugins = new ArrayList<>();
            // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
            // 上游原本直接用原 jar 建 ClassLoader：
            //     new PluginJarClassLoader(pluginFile, ...)
            // URLClassLoader 会一直持有 jar 的文件句柄，Windows 上原 jar 就被锁住了，
            // 覆盖不了 —— 调试时反复替换脚本 jar 很不方便。
            //
            // 本地改成先把原 jar 复制到运行时目录，再用副本建 ClassLoader。
            // 复制完成后原 jar 没有任何进程持有句柄，可以随时覆盖。
            //
            // 用副本而不是"加载完就 close ClassLoader"，是因为 close 之后
            // 运行时再想加载 jar 里的类（懒加载的依赖库）会失败，
            // 而读 jar 里的资源文件也会失效。副本没有这个副作用。
            //
            // 冲突时如何覆盖：保留这几行。改回直接用 pluginFile 建 ClassLoader
            // 会让原 jar 重新被锁住。
            // ===================== [MICROBOT 本地改动 END] =====================
            File runtimeCopy = createRuntimeCopy(pluginFile, internalName);
            PluginJarClassLoader classLoader = new PluginJarClassLoader(runtimeCopy, getClass().getClassLoader());
            loaders.put(internalName, classLoader);
            for (ClassPath.ClassInfo classInfo : ClassPath.from(classLoader).getAllClasses()) {
                try {
                    Class<?> clazz = classLoader.loadClass(classInfo.getName());
                    plugins.add(clazz);
                } catch (ClassNotFoundException e) {
                    log.trace("Class not found during sideloading: {}", classInfo.getName(), e);
                } catch(Throwable t) {
                    log.error("Incompatible plugin found: " + internalName);
                    StartupRecovery.pluginFailed(internalName, getInstalledPluginVersion(internalName).orElse(null), "class loading", t);
                }
            }
            if (loadPlugins(plugins, null).isEmpty() && loaders.remove(internalName, classLoader)) {
                classLoader.close();
                log.warn("Side-load jar {} produced no plugins", internalName);
            } else {
                log.info("Loaded side-load jar {}", internalName);
            }
        } catch (PluginInstantiationException | IOException e) {
            log.trace("Error loading side-loaded plugin!", e);
        }
    }

    public void loadSideLoadPlugins() {
        if (safeMode) {
            log.warn("Safe mode is enabled, skipping loading of sideloaded plugins.");
            return;
        }
        File[] files = createSideloadingFolder();
        if (files == null) {
            return;
        }
        // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
        // 上游原本拿「已加载插件的类名集合」去和 jar 文件名比，判断这个 jar 加载过没有。
        // 这依赖「jar 文件名 == 插件类名」这个假设：一个 jar 装多个脚本时对不上，
        // 于是每次都会重新加载一遍，重载后 pluginManager 里会多出重复的同名插件。
        // 改成直接查 loaders —— 它的 key 就是 jar 的 internalName（jar 文件名），
        // 是判断"这个 jar 加载过没有"的正确依据。
        //
        // 冲突时如何覆盖：保留这两行。
        // ===================== [MICROBOT 本地改动 END] =====================
        for (File f : files) {
            if (!f.getName().endsWith(".jar")) {
                continue;
            }
            String internalName = f.getName().replace(".jar", "");
            if (loaders.containsKey(internalName)) {
                log.debug("Side-load jar {} is already loaded, skipping", internalName);
                continue;
            }
            try {
                loadSideLoadPlugin(internalName);
            } catch (Exception exception) {
                System.out.println("Error loading side-loaded plugin: " + internalName);
            }
        }
        eventBus.post(new ExternalPluginsChanged());
    }

    // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
    // 本地新增：为工具栏的「启动」按钮提供「全量重新加载脚本 jar」的能力。
    //
    // 上游原本只有 loadSideLoadPlugins()，它是增量语义 —— 只加载目录里新出现的 jar，
    // 已经加载过的按类名跳过，既不停掉也不卸载任何东西。
    //
    // 本地新增这一组方法，做全量语义：
    //   reloadSideLoadPlugins()  —— 停掉并卸载所有 side-load 脚本，再重新扫描目录加载一遍
    //   unloadSideLoadPlugins()  —— 只做停掉+卸载，不重新加载
    //   unloadSideLoadPlugin(名) —— 停掉+卸载单个脚本
    //   findSideLoadPlugin(...)  —— 判定某个插件是不是由指定 jar 的 ClassLoader 加载的
    //   stopAndRemove(插件)      —— 停插件并从插件管理器摘掉
    //
    // 三条关键约束（改这个文件时必须一起维护）：
    //   1. 判定归属要同时满足「类名匹配」和「ClassLoader 相同」。只比类名的话，
    //      碰巧同名的另一个插件会被误停。详见 findSideLoadPlugin 的注释。
    //   2. PluginManager.stopPlugin 里有 assert SwingUtilities.isEventDispatchThread()，
    //      停插件必须在 EDT 上做，所以 stopAndRemove 内部自己切线程。
    //   3. 必须关掉 ClassLoader 才能释放 jar 文件句柄（Windows 上尤其重要），
    //      否则同一个 jar 再加载会失败。
    //
    // 冲突时如何覆盖：整块保留。这组方法是纯新增，没有改动上游任何原有逻辑，
    // 上游更新后若此处冲突，直接保留本地这一段即可。
    // 但 remove() 里有一段配套的替换，见下方另一处标记。
    // ===================== [MICROBOT 本地改动 END] =====================

    /**
     * 把原 jar 复制到运行时目录，返回副本。
     *
     * <p>复制用的就是普通文件拷贝，读完就释放句柄，所以原 jar 之后可以随便覆盖。
     *
     * @param pluginFile  原 jar
     * @param internalName 脚本的内部名
     * @return 副本文件
     */
    // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
    // 本地新增。配合 loadSideLoadPlugin 用副本加载，让原 jar 不被占用。
    //
    // 副本用带序号的名字，避免"目标文件已存在"的问题：
    // 上一个副本可能还被上一轮的 ClassLoader 占着，写不进去。
    //
    // 冲突时如何覆盖：保留整个方法。
    // ===================== [MICROBOT 本地改动 END] =====================
    private File createRuntimeCopy(File pluginFile, String internalName) throws IOException {
        // 先把上一轮的副本清掉，能删就删
        deleteRuntimeCopy(internalName);

        if (!RUNTIME_PLUGIN_DIR.isDirectory() && !RUNTIME_PLUGIN_DIR.mkdirs()) {
            throw new IOException("Could not create runtime plugin directory " + RUNTIME_PLUGIN_DIR);
        }

        File copy = new File(RUNTIME_PLUGIN_DIR, internalName + "-" + System.nanoTime() + ".jar");
        try (InputStream in = new FileInputStream(pluginFile);
             java.io.OutputStream out = new FileOutputStream(copy)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        runtimeCopies.put(internalName, copy);
        return copy;
    }

    /**
     * 删掉某个脚本的运行时副本。
     */
    // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
    // 本地新增。脚本卸载时调用，删掉它占用的副本。
    //
    // 冲突时如何覆盖：保留整个方法。
    // ===================== [MICROBOT 本地改动 END] =====================
    private void deleteRuntimeCopy(String internalName) {
        File copy = runtimeCopies.remove(internalName);
        if (copy == null) {
            return;
        }
        try {
            // 这个文件已经 import 了 com.google.common.io.Files，所以 nio 的要用全限定名
            java.nio.file.Files.deleteIfExists(copy.toPath());
        } catch (IOException e) {
            log.warn("Could not delete runtime copy for {}", internalName, e);
        }
    }

    /**
     * 清空运行时副本目录。
     *
     * <p>客户端退出时调用。脚本运行中崩掉的话会留下残留，
     * 下次启动时会被同名副本覆盖，不用额外清理。
     */
    // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
    // 本地新增。客户端退出时调用。
    //
    // 冲突时如何覆盖：保留整个方法。
    // ===================== [MICROBOT 本地改动 END] =====================
    private void cleanupRuntimeCopies() {
        for (String internalName : new ArrayList<>(runtimeCopies.keySet())) {
            deleteRuntimeCopy(internalName);
        }
        File[] files = RUNTIME_PLUGIN_DIR.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                java.nio.file.Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                log.warn("Could not delete runtime copy {}", file.getName(), e);
            }
        }
    }

    /**
     * 全量重新加载 side-load 目录里的所有脚本。
     *
     * <p>会先停掉并卸载当前已加载的脚本，再重新扫描目录加载一遍。
     * <b>正在运行的脚本会被打断。</b>
     *
     * <p>停插件必须在 EDT 上执行（{@code PluginManager.stopPlugin} 断言这一点），
     * 所以这个方法要么在 EDT 上调，要么它自己切过去。
     */
    public void reloadSideLoadPlugins() {
        log.info("Reloading side-load plugins. Before: {}", describeLoadedExternalPlugins());
        unloadSideLoadPlugins();
        loadSideLoadPlugins();
        log.info("Reloaded side-load plugins. After: {}", describeLoadedExternalPlugins());
    }

    // ===================== [MICROBOT 本地改动] --script 启动指定脚本 =====================
    // 本地新增：返回所有由 side-load 目录里的 jar 加载的插件。
    //
    // 判定方式：比较插件类的 ClassLoader 和 loaders 表里的 loader 是不是同一个对象。
    // 这跟 findSideLoadPlugins 用的是同一套判定，只是那边是包私有的静态方法。
    //
    // 只认 side-load 的 jar：客户端内置插件、以及从 pluginhub 下载的外部插件都不算。
    //
    // 冲突时如何覆盖：保留整个方法。
    // ===================== [MICROBOT 本地改动 END] =====================
    public List<Plugin> getSideLoadPlugins() {
        List<Plugin> result = new ArrayList<>();
        for (URLClassLoader loader : loaders.values()) {
            result.addAll(findSideLoadPlugins(pluginManager.getPlugins(), loader));
        }
        return result;
    }

    /**
     * 找出这个插件是从哪个 side-load jar 加载的。
     *
     * <p>jar 的内部名（文件名去掉 .jar）跨重载是稳定的 —— 重载会换一个
     * ClassLoader，但 jar 文件名不变。所以重启脚本时可以用它来定位
     * 「重载后的那一个」，光靠类名不够：同名同包的类装在不同的 jar 里
     * 会有两个候选。
     *
     * @param plugin 插件
     * @return jar 的内部名，不是 side-load 来的返回 null
     */
    // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
    // 本地新增。配合上面的 findSideLoadPlugins 用。
    //
    // 上游原本没有这个方法。它要解决的是「重载后怎么找到刚刚加载的那一个」：
    // 重载会换一个 ClassLoader，同一个 jar 里的类会拿到新的 Class 对象，
    // 光靠类名定位会有两个候选（同名同包的类装在不同的 jar 里）。
    // 改成用 jar 的 internalName（jar 文件名去掉 .jar）—— 它跨重载是稳定的。
    //
    // 冲突时如何覆盖：保留整个方法。
    // ===================== [MICROBOT 本地改动 END] =====================
    @Nullable
    public String findSideLoadJarName(Plugin plugin) {
        if (plugin == null) {
            return null;
        }
        for (Map.Entry<String, URLClassLoader> entry : loaders.entrySet()) {
            if (!findSideLoadPlugins(Collections.singletonList(plugin), entry.getValue()).isEmpty()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 把当前已加载的 external 插件列成一行字符串，用于日志。
     */
    private String describeLoadedExternalPlugins() {
        return pluginManager.getPlugins().stream()
                .filter(p -> {
                    PluginDescriptor d = p.getClass().getAnnotation(PluginDescriptor.class);
                    return d != null && d.isExternal();
                })
                .map(p -> p.getClass().getSimpleName() + "@" + p.getClass().getClassLoader().getClass().getSimpleName())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * 停掉并卸载所有从 side-load 目录加载的脚本。
     *
     * <p>只认由 side-load 的 ClassLoader 加载的插件，客户端内置的插件不受影响。
     */
    public void unloadSideLoadPlugins() {
        for (String internalName : new ArrayList<>(loaders.keySet())) {
            unloadSideLoadPlugin(internalName);
        }
    }

    /**
     * 停掉并卸载一个从 side-load 目录加载的脚本。
     *
     * @param internalName 脚本的内部名，也就是 jar 文件名去掉 .jar
     */
    private void unloadSideLoadPlugin(String internalName) {
        URLClassLoader jarLoader = loaders.get(internalName);
        if (jarLoader == null) {
            return;
        }

        // 按 ClassLoader 判定归属：这个 jar 加载出来的插件全部都要卸掉。
        // 一个 jar 里装多个脚本时，只按 jar 名去匹配类名会漏掉一部分。
        List<Plugin> owned = findSideLoadPlugins(pluginManager.getPlugins(), jarLoader);
        loaders.remove(internalName);

        for (Plugin plugin : owned) {
            stopAndRemove(plugin);
        }

        try {
            if (jarLoader != null) {
                jarLoader.close();
            }
        } catch (Exception ignored) {
        }

        // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
        // 删掉运行时副本。ClassLoader 关掉之后副本就不再被占用，可以删了。
        //
        // 冲突时如何覆盖：保留这一行。
        // ===================== [MICROBOT 本地改动 END] =====================
        deleteRuntimeCopy(internalName);

        log.info("Unloaded side-load plugin jar {} ({} plugin(s))", internalName, owned.size());
    }

    /**
     * 找出由指定 ClassLoader 加载的所有插件。
     *
     * <p>用 ClassLoader 判定归属，<b>不依赖「jar 文件名 == 插件类名」这个假设</b>。
     * 上游（以及本地最初的写法）拿 internalName 去和类的 simpleName 比，
     * 一个 jar 里装多个脚本时对不上，脚本就永远卸不掉 —— 旧的还挂在插件管理器上，
     * 而 ClassLoader 已经被关掉，重载后就会多出重复的同名插件。
     *
     * <p>同名但来自别的 ClassLoader 的插件不会命中，那正是要保护的对象。
     *
     * @param plugins 所有已注册的插件
     * @param loader  side-load 用的 ClassLoader
     * @return 由该 ClassLoader 加载的插件
     */
    @Nullable
    static List<Plugin> findSideLoadPlugins(Collection<Plugin> plugins, ClassLoader loader) {
        if (loader == null) {
            return Collections.emptyList();
        }
        return plugins.stream()
                .filter(x -> x.getClass().getClassLoader() == loader)
                .collect(Collectors.toList());
    }

    /**
     * 停掉一个插件并从插件管理器里摘掉。
     *
     * <p>停插件必须在 EDT 上做。
     */
    private void stopAndRemove(Plugin plugin) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                pluginManager.stopPlugin(plugin);
            } else {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        pluginManager.stopPlugin(plugin);
                    } catch (PluginInstantiationException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Error stopping plugin {}: {}", plugin.getClass().getSimpleName(), e.getMessage());
        }
        pluginManager.remove(plugin);
    }

    /**
     * Topologically sort a graph. Uses Kahn's algorithm.
     *
     * @param graph - A directed graph
     * @param <T>   - The type of the item contained in the nodes of the graph
     * @return - A topologically sorted list corresponding to graph.
     * <p>
     * Multiple invocations with the same arguments may return lists that are not equal.
     */
    @VisibleForTesting
    static <T> List<T> topologicalSort(Graph<T> graph) {
        MutableGraph<T> graphCopy = Graphs.copyOf(graph);
        List<T> l = new ArrayList<>();
        Set<T> s = graphCopy.nodes().stream()
                .filter(node -> graphCopy.inDegree(node) == 0)
                .collect(Collectors.toSet());
        while (!s.isEmpty()) {
            Iterator<T> it = s.iterator();
            T n = it.next();
            it.remove();

            l.add(n);

            for (T m : new HashSet<>(graphCopy.successors(n))) {
                graphCopy.removeEdge(n, m);
                if (graphCopy.inDegree(m) == 0) {
                    s.add(m);
                }
            }
        }
        if (!graphCopy.edges().isEmpty()) {
            throw new RuntimeException("Graph has at least one cycle");
        }
        return l;
    }

    private List<Plugin> loadPlugins(List<Class<?>> plugins, BiConsumer<Integer, Integer> onPluginLoaded) throws PluginInstantiationException {
        MutableGraph<Class<? extends Plugin>> graph = GraphBuilder
                .directed()
                .build();

        // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
        // 上游原本按 Class 对象判断"这个插件是否已经加载"。
        // 全量重载会新建 ClassLoader，同一个 jar 里的类会拿到新的 Class 对象，
        // 于是这个检查挡不住重复加载 —— 重载后 pluginManager 里会同时存在新旧两份同名插件。
        // 改成按全限定类名判断：只要 pluginManager 里已经有同名的插件，就不再重复加载。
        // 用全限定名而不是简单名，避免 a.Foo 和 b.Foo 这种同名不同包的插件被误挡。
        //
        // 冲突时如何覆盖：保留这段替换。上游更新后若此处冲突，
        // 把判断改回按 Class 对象比较会重新引入"重载后出现重复插件"的问题。
        // ===================== [MICROBOT 本地改动 END] =====================
        Set<String> alreadyLoaded = pluginManager.getPlugins().stream()
                .map(p -> p.getClass().getName())
                .collect(Collectors.toSet());

        for (Class<?> clazz : plugins) {
            if (alreadyLoaded.contains(clazz.getName())) {
                log.debug("Plugin {} is already loaded, skipping duplicate.", clazz.getSimpleName());
                continue;
            }
            PluginDescriptor pluginDescriptor = clazz.getAnnotation(PluginDescriptor.class);

            if (pluginDescriptor == null) {
                if (clazz.getSuperclass() == Plugin.class) {
                    log.error("Class {} is a plugin, but has no plugin descriptor", clazz);
                }
                continue;
            }

            if (clazz.getSuperclass() != Plugin.class) {
                log.error("Class {} has plugin descriptor, but is not a plugin", clazz);
                continue;
            }

            if (pluginDescriptor.isExternal()) {
                String blockReason = getLoadBlockReason(clazz.getSimpleName(), pluginDescriptor);
                if (blockReason != null) {
                    log.error("Plugin {} was not loaded: {}", clazz.getSimpleName(), blockReason);
                    blockedPlugins.put(clazz.getSimpleName(), blockReason);
                    StartupRecovery.pluginFailed(clazz.getSimpleName(), pluginDescriptor.version(), "compatibility check", blockReason);
                    continue;
                }
                blockedPlugins.remove(clazz.getSimpleName());
            } else if (pluginDescriptor.disable()) {
                log.error("Plugin {} has been disabled upstream", clazz.getSimpleName());
                StartupRecovery.pluginFailed(clazz.getSimpleName(), pluginDescriptor.version(), "compatibility check", "disabled upstream");
                continue;
            }

            graph.addNode((Class<Plugin>) clazz);
        }

        for (Class<? extends Plugin> pluginClazz : graph.nodes()) {
            PluginDependency[] pluginDependencies = pluginClazz.getAnnotationsByType(PluginDependency.class);

            for (PluginDependency pluginDependency : pluginDependencies) {
                if (graph.nodes().contains(pluginDependency.value())) {
                    graph.putEdge(pluginDependency.value(), pluginClazz);
                }
            }
        }

        if (Graphs.hasCycle(graph)) {
            throw new PluginInstantiationException("Plugin dependency graph contains a cycle!");
        }

        List<Class<? extends Plugin>> sortedPlugins = topologicalSort(graph);

        int loaded = 0;
        List<Plugin> newPlugins = new ArrayList<>();
        for (Class<? extends Plugin> pluginClazz : sortedPlugins) {
            Plugin plugin;
            try {
                plugin = instantiate(pluginManager.getPlugins(), (Class<Plugin>) pluginClazz);
                log.info("Plugin loaded {}", plugin.getClass().getSimpleName());
                newPlugins.add(plugin);
                pluginManager.addPlugin(plugin);
                loaded++;
            } catch (PluginInstantiationException ex) {
                log.error("Error instantiating plugin!", ex);
                StartupRecovery.pluginFailed(pluginClazz.getSimpleName(), pluginClazz.getAnnotation(PluginDescriptor.class).version(), "load", ex);
            }

            if (onPluginLoaded != null) {
                onPluginLoaded.accept(loaded, sortedPlugins.size());
            }
        }

        return newPlugins;
    }

    private Plugin instantiate(Collection<Plugin> scannedPlugins, Class<Plugin> clazz) throws PluginInstantiationException {
        PluginDependency[] pluginDependencies = clazz.getAnnotationsByType(PluginDependency.class);
        List<Plugin> deps = new ArrayList<>();
        for (PluginDependency pluginDependency : pluginDependencies) {
            Optional<Plugin> dependency = scannedPlugins.stream().filter(p -> p.getClass() == pluginDependency.value()).findFirst();
            if (!dependency.isPresent()) {
                throw new PluginInstantiationException("Unmet dependency for " + clazz.getSimpleName() + ": " + pluginDependency.value().getSimpleName());
            }
            deps.add(dependency.get());
        }

        Plugin plugin;
        try {
            plugin = clazz.getDeclaredConstructor().newInstance();
        } catch (ThreadDeath e) {
            throw e;
        } catch (Throwable ex) {
            throw new PluginInstantiationException(ex);
        }

        try {
            Injector parent = Microbot.getInjector();

            if (deps.size() > 1) {
                List<com.google.inject.Module> modules = new ArrayList<>(deps.size());
                for (Plugin p : deps) {
                    com.google.inject.Module module = (Binder binder) ->
                    {
                        binder.bind((Class<Plugin>) p.getClass()).toInstance(p);
                        binder.install(p);
                    };
                    modules.add(module);
                }

                parent = parent.createChildInjector(modules);
            } else if (!deps.isEmpty()) {
                parent = deps.get(0).getInjector();
            }

            Module pluginModule = (Binder binder) ->
            {
                binder.bind(clazz).toInstance(plugin);
                binder.install(plugin);
            };
            Injector pluginInjector = parent.createChildInjector(pluginModule);
            System.out.println(pluginInjector.getClass().getSimpleName());
            plugin.setInjector(pluginInjector);
        } catch (com.google.common.util.concurrent.ExecutionError e) {
            // Guice/Guava wraps NoClassDefFoundError here
            Throwable cause = e.getCause();
            if (cause instanceof NoClassDefFoundError) {
                log.error("Missing class while loading plugin {}: {}", clazz.getSimpleName(), cause.toString());
            } else {
                log.error("Error while loading plugin {}: {}", clazz.getSimpleName(), e.toString(), e);
            }

            File jar = getPluginJarFile(plugin.getClass().getSimpleName());
            if (jar != null) {
                jar.delete();
            }
            throw new PluginInstantiationException(e);
        } catch (ThreadDeath e) {
            throw e;
        } catch (Throwable ex) {
            log.error("Incompatible plugin found: " + clazz.getSimpleName());
            File jar = getPluginJarFile(plugin.getClass().getSimpleName());
            jar.delete();
            throw new PluginInstantiationException(ex);
        }

        log.debug("Loaded plugin {}", clazz.getSimpleName());
        return plugin;
    }

    /**
     * Determines if a class is a Microbot-related Plugin that should be loaded.
     * This includes plugins from utility packages, UI components, and specific Microbot systems.
     *
     * @param clazz the class to check
     * @return true if the class should be included in Microbot plugin loading
     */
    private static boolean isMicrobotRelatedPlugin(Class<?> clazz) {
        if (clazz == null || clazz.getPackage() == null) {
            return false;
        }

        if (!Plugin.class.isAssignableFrom(clazz) || clazz == Plugin.class) {
            return false;
        }

        PluginDescriptor descriptor = clazz.getAnnotation(PluginDescriptor.class);
        if (descriptor == null) {
            return false;
        }

        String pkg = clazz.getPackage().getName();

        if (pkg.startsWith(PLUGIN_PACKAGE)) {
            return pkg.equals(PLUGIN_PACKAGE)
                    || pkg.contains(".ui")
                    || pkg.contains(".util")
                    || pkg.contains(".shortestpath")
                    || pkg.contains(".questhelper")
                    || pkg.contains("inventorysetups")
                    || pkg.contains("breakhandler");
        }

        return false;
    }

    /**
     * Scans the classpath for Microbot-related Plugin classes and returns them.
     * This includes plugin classes from utility packages, UI components, and specific Microbot systems.
     *
     * @return list of Microbot-related Plugin classes found on the classpath
     */
    private List<Class<?>> scanForMicrobotPlugins() {
        List<Class<?>> microbotPlugins = new ArrayList<>();

        try {
            ClassPath classPath = ClassPath.from(getClass().getClassLoader());

            for (ClassPath.ClassInfo classInfo : classPath.getAllClasses()) {
                if (!classInfo.getPackageName().startsWith(PLUGIN_PACKAGE)) {
                    continue;
                }

                try {
                    Class<?> clazz = classInfo.load();
                    if (isMicrobotRelatedPlugin(clazz)) {
                        microbotPlugins.add(clazz);
                        log.debug("Found Microbot plugin class: {}", clazz.getName());
                    }
                } catch (Throwable e) {
                    log.trace("Could not load class during Microbot scan: {}", classInfo.getName(), e);
                }
            }

            log.info("Found {} additional Microbot plugin classes during classpath scan", microbotPlugins.size());
        } catch (IOException e) {
            log.error("Failed to scan classpath for Microbot plugin classes", e);
        }

        return microbotPlugins;
    }

    public void loadCorePlugins(List<Class<?>> plugins) throws PluginInstantiationException {
        SplashScreen.stage(.59, null, "Loading plugins");
        List<Class<?>> combinedPlugins = new ArrayList<>(plugins);

        List<Class<?>> additionalMicrobotPlugins = scanForMicrobotPlugins();

        Set<Class<?>> existingPlugins = new HashSet<>(plugins);

        List<Class<?>> newMicrobotPlugins = additionalMicrobotPlugins.stream()
                .filter(clazz -> !existingPlugins.contains(clazz))
                .collect(Collectors.toList());

        combinedPlugins.addAll(newMicrobotPlugins);

        log.info("Loading core plugins: {} passed in + {} core Microbot plugins = {} total",
                plugins.size(), newMicrobotPlugins.size(), combinedPlugins.size());

        if (!combinedPlugins.isEmpty()) {
            loadPlugins(combinedPlugins, (loaded, total) ->
                    SplashScreen.stage(.60, .70, null, "Loading Microbot plugins", loaded, total, false));
        }
    }

    @Subscribe
    public void onClientShutdown(ClientShutdown shutdown) {
        log.info("Client shutdown detected, stopping all Microbot plugins");
        shutdown();
        StartupRecovery.cleanExit();
    }

    /**
     * Refreshes plugins
     */
    private void refresh() {
        if (safeMode) {
            log.warn("Safe mode is enabled, skipping loading of sideloaded plugins.");
            return;
        }

        if (isShuttingDown.get()) {
            return;
        }

        synchronized (this) {
            if (profileRefreshInProgress) {
                return;
            }
            profileRefreshInProgress = true;
        }

        try {
            log.debug("Starting plugin refresh");

            List<Plugin> installedPlugins = getInstalledPlugins();

            List<Plugin> disabledPlugins = installedPlugins.stream()
                    .filter(plugin -> {
                        MicrobotPluginManifest upstreamManifest = manifestMap.get(plugin.getClass().getSimpleName());
                        return upstreamManifest != null && upstreamManifest.isDisable();
                    })
                    .collect(Collectors.toList());

            if (!disabledPlugins.isEmpty()) {
                log.warn("Found {} disabled plugin(s) that have been disabled upstream:", disabledPlugins.size());
                for (Plugin disabledPlugin : disabledPlugins) {
                    log.warn("  - Plugin '{}' has been disabled upstream and will be removed from your installed plugins",
                            disabledPlugin.getClass().getSimpleName());
                }

                List<Plugin> enabledPlugins = installedPlugins.stream()
                        .filter(plugin -> {
                            MicrobotPluginManifest upstreamManifest = manifestMap.get(plugin.getClass().getSimpleName());
                            return upstreamManifest == null || !upstreamManifest.isDisable();
                        })
                        .collect(Collectors.toList());

                installedPlugins = enabledPlugins;

                log.info("Automatically removed {} disabled plugin(s) from your installed plugins list", disabledPlugins.size());
            }

            Set<String> installedNames = installedPlugins.stream()
                    .map(plugin -> plugin.getClass().getSimpleName())
                    .collect(Collectors.toSet());

            List<Plugin> allLoadedPlugins = new ArrayList<>(pluginManager.getPlugins());

            List<Plugin> loadedExternalPlugins = allLoadedPlugins.stream()
                    .filter(plugin -> {
                        PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
                        if (descriptor == null || !descriptor.isExternal()) {
                            return false;
                        }
                        String packageName = plugin.getClass().getPackage().getName();
                        return packageName.contains("microbot");
                    })
                    .collect(Collectors.toList());

            Set<String> loadedByInternalName = loadedExternalPlugins.stream()
                    .map(this::getPluginManifest) // your helper
                    .filter(Objects::nonNull)
                    .map(MicrobotPluginManifest::getInternalName)
                    .collect(Collectors.toSet());

            log.info("Profile refresh - Installed plugins: {}, Currently loaded Microbot plugins: {}",
                    installedNames, loadedByInternalName);

            log.debug("All loaded plugins ({}):", allLoadedPlugins.size());
            for (Plugin plugin : allLoadedPlugins) {
                PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
                boolean isExternal = descriptor != null && descriptor.isExternal();
                MicrobotPluginManifest manifest = getPluginManifest(plugin);
                log.debug("  - {} (external: {}, has manifest: {})",
                        plugin.getClass().getSimpleName(), isExternal, manifest != null);
            }

            // TODO: current code will redownload all plugins, because manifestMap is a manifest of all the plugins on the hub
            // we need to filter it down to only the ones the user has installed
            // what i think we need to do:
            // create a filtered down list of manifestmap that contains only  the installed plugins
            // add your "install" plugin to this list, then use this list here
            var userManifestMap = manifestMap.entrySet().stream()
                    .filter(e -> installedNames.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));


            Set<String> needsDownload = userManifestMap.keySet().stream()
                    .filter(microbotPluginManifest -> !getPluginJarFile(microbotPluginManifest).exists())
                    .collect(Collectors.toSet());

            Set<String> needsRedownload = userManifestMap.keySet().stream()
                    .filter(pluginName -> {
                        File pluginFile = getPluginJarFile(pluginName);
                        if (!pluginFile.exists()) {
                            return false;
                        }
                        if (!verifyHash(pluginName)) {
                            log.info("Hash verification failed for plugin: {}. Marking for redownload.", pluginName);
                            if (pluginFile.delete()) {
                                log.info("Deleted outdated plugin file: {}", pluginFile.getClass().getSimpleName());
                            } else {
                                log.warn("Failed to delete outdated plugin file: {}", pluginFile.getAbsolutePath());
                            }
                            return true;
                        }
                        return false;
                    })
                    .collect(Collectors.toSet());

            needsDownload.addAll(needsRedownload);

            Set<String> needsReload = new HashSet<>(needsDownload);

            Set<File> keepFiles = userManifestMap.keySet().stream()
                    .map(this::getPluginJarFile)
                    .filter(File::exists)
                    .collect(Collectors.toSet());

            Instant now = Instant.now();
            Instant keepAfter = now.minus(3, ChronoUnit.DAYS);

            Optional.ofNullable(PLUGIN_DIR.listFiles((dir, name) -> name.endsWith(".jar"))).stream()
                    .flatMap(Arrays::stream)
                    .filter(file -> !keepFiles.contains(file) && file.lastModified() < keepAfter.toEpochMilli())
                    .forEach(file -> {
                        log.info("Cleaning up old plugin file (>3 days): {}", file.getName());
                        if (!file.delete()) {
                            log.warn("Failed to delete old plugin file: {}", file.getAbsolutePath());
                        }
                    });

            for (String pluginName : needsDownload) {
                log.info("Downloading missing plugin: {}", pluginName);
                String desiredVersion = getInstalledPluginVersion(pluginName)
                        .orElse(null);
                downloadPlugin(pluginName, desiredVersion);
            }

            Set<String> installedPluginNames = userManifestMap.values().stream()
                    .map(MicrobotPluginManifest::getInternalName)
                    .collect(Collectors.toSet());

            Set<MicrobotPluginManifest> toAdd = userManifestMap.values().stream()
                    .filter(m -> needsReload.contains(m.getInternalName())
                            || !loadedByInternalName.contains(m.getInternalName()))
                    .collect(Collectors.toSet());

            List<Plugin> toRemove = loadedExternalPlugins.stream()
                    .filter(p -> {
                        MicrobotPluginManifest m = getPluginManifest(p);
                        if (m == null) return true; // unknown → remove
                        String name = m.getInternalName();
                        return !installedPluginNames.contains(name) || needsReload.contains(name);
                    })
                    .collect(Collectors.toList());

            log.info("Plugin refresh - Will add: {} plugins, Will remove: {} plugins",
                    toAdd.stream().map(MicrobotPluginManifest::getInternalName).collect(Collectors.toSet()),
                    toRemove.stream().map(p -> p.getClass().getSimpleName()).collect(Collectors.toSet()));

            for (Plugin plugin : toRemove) {
                String simple = plugin.getClass().getSimpleName();
                log.info("Stopping plugin \"{}\"", simple);
                try {
                    SwingUtilities.invokeAndWait(() -> stopPlugin(plugin));
                } catch (InterruptedException | InvocationTargetException e) {
                    log.warn("Failed to stop plugin {}", simple, e);
                }
            }


            for (MicrobotPluginManifest manifest : toAdd) {
                String pluginName = manifest.getInternalName();
                File pluginFile = getPluginJarFile(pluginName);
                if (!pluginFile.exists()) {
                    log.warn("Plugin file missing for {}, skipping load", pluginName);
                    continue;
                }

                log.info("Loading plugin \"{}\"", pluginName);
                List<Plugin> newPlugins = null;
                PluginJarClassLoader classLoader = null;
                try {
                    if (!verifyHash(pluginName)) {
                        log.warn("Plugin hash verification failed for: {}. The installed version may be outdated or from a different source.", pluginName);
                        continue;
                    }

                    List<Class<?>> pluginClasses = new ArrayList<>();
                    classLoader = new PluginJarClassLoader(pluginFile, getClass().getClassLoader());

                    for (ClassPath.ClassInfo classInfo : ClassPath.from(classLoader).getAllClasses()) {
                        try {
                            Class<?> clazz = classLoader.loadClass(classInfo.getName());
                            pluginClasses.add(clazz);
                        } catch (ClassNotFoundException e) {
                            log.trace("Class not found during plugin loading: {}", classInfo.getName(), e);
                        } catch(Throwable t) {
                            log.error("Incompatible plugin found: " + pluginName);
                            StartupRecovery.pluginFailed(pluginName, manifest.getVersion(), "class loading", t);
                        }
                    }

                    newPlugins = loadPlugins(pluginClasses, null);

                    boolean startup = SplashScreen.isOpen();
                    if (!startup && !newPlugins.isEmpty()) {
                        pluginManager.loadDefaultPluginConfiguration(newPlugins);
                        final List<Plugin> pluginsToStart = newPlugins;
                        SwingUtilities.invokeAndWait(() -> {
                            try {
                                for (Plugin p : pluginsToStart) {
                                    pluginManager.startPlugin(p);
                                }
                            } catch (PluginInstantiationException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                    log.info("Successfully loaded plugin: {}", pluginName);
                } catch (ThreadDeath e) {
                    throw e;
                } catch (Throwable e) {
                    log.warn("Unable to load or start plugin \"{}\"", pluginName, e);
                    StartupRecovery.pluginFailed(pluginName, manifest.getVersion(), "load or start", e);
                }
            }

            if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
                eventBus.post(new ExternalPluginsChanged());
            }
            log.info("Completed plugin refresh - Added: {}, Removed: {}", toAdd.size(), toRemove.size());
        } catch (Exception e) {
            log.error("Error during plugin refresh", e);
        } finally {
            profileRefreshInProgress = false;
        }
    }

    /**
     * Downloads a plugin JAR file from the remote server.
     *
     * @param internalName the internal name of the plugin to download
     * @return true if the plugin was successfully downloaded, false otherwise
     */
    private boolean downloadPlugin(String internalName, @Nullable String versionOverride) {
        MicrobotPluginManifest manifest = manifestMap.get(internalName);
        if (manifest == null) {
            log.error("Cannot download plugin {}: manifest not found", internalName);
            return false;
        }

        try {
            File pluginFile = getPluginJarFile(internalName);

            String versionToDownload = !Strings.isNullOrEmpty(versionOverride) ? versionOverride : manifest.getVersion();
            if (Strings.isNullOrEmpty(versionToDownload)) {
                log.error("Cannot determine version to download for {}", internalName);
                return false;
            }

            HttpUrl jarUrl = microbotPluginClient.getJarURL(manifest, versionToDownload);
            if (jarUrl == null || !jarUrl.isHttps()) {
                log.error("Invalid JAR URL for plugin {}", internalName);
                return false;
            }

            OkHttpClient clientWithoutProxy = noProxy(okHttpClient);
            Request request = new Request.Builder()
                    .url(jarUrl)
                    .build();

            log.info("from url : " + jarUrl);

            try (Response response = clientWithoutProxy.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Failed to download plugin {}: HTTP {}", internalName, response.code());
                    return false;
                }

                byte[] jarData = response.body().bytes();

                Files.write(jarData, pluginFile);
                log.info("Plugin {} (version {}) downloaded to {}", internalName, versionToDownload, pluginFile.getAbsolutePath());

                String authoritativeHash = versionToDownload.equals(manifest.getVersion()) ? manifest.getSha256() : null;
                if (Strings.isNullOrEmpty(authoritativeHash)) {
                    authoritativeHash = calculateHash(internalName);
                }
                if (!Strings.isNullOrEmpty(authoritativeHash)) {
                    rememberInstalledPluginVersion(internalName, versionToDownload, authoritativeHash);
                }

                return true;
            }

        } catch (Exception e) {
            log.error("Failed to download plugin {}", internalName, e);

            File pluginFile = getPluginJarFile(internalName);
            if (pluginFile.exists() && !pluginFile.delete()) {
                log.warn("Failed to delete corrupted plugin file: {}", pluginFile.getAbsolutePath());
            }
            return false;
        }
    }

    /**
     * Installs a plugin and triggers UI refresh.
     *
     * @param manifest the manifest of the plugin to install
     */
    public Future<?> installPlugin(MicrobotPluginManifest manifest, @Nullable String versionOverride) {
        return executor.submit(() -> install(manifest, versionOverride));
    }

    /**
     * Removes a plugin and triggers UI refresh.
     *
     * @param manifest the manifest of the plugin to remove
     */
    public Future<?> removePlugin(MicrobotPluginManifest manifest) {
        return executor.submit(() -> remove(manifest));
    }

    /**
     * Installs a plugin by adding it to the installed plugins list in config.
     *
     * @param manifest the manifest of the plugin to install
     */
    public void install(MicrobotPluginManifest manifest, @Nullable String versionOverride) {
        if (manifest == null || !manifestMap.containsValue(manifest)) {
            log.error("Can't install plugin: unable to identify manifest");
            return;
        }

        final String internalName = manifest.getInternalName();
        if (internalName == null || internalName.isEmpty()) {
            log.error("Cannot install plugin: internal name is null or empty");
            return;
        }

        if (manifest.isDisable()) {
            log.warn("Cannot install plugin '{}' ({}): This plugin has been disabled upstream by the developers. " +
                            "This usually means the plugin is no longer functional, has security issues, or has been deprecated.",
                    manifest.getDisplayName(), internalName);
            return;
        }

        MicrobotPluginHealth health = getPluginHealth(manifest, versionOverride);
        if (health.getState() == MicrobotPluginHealth.State.BROKEN) {
            log.warn("Cannot install plugin '{}' ({}) version {}: this version is confirmed broken upstream.",
                    manifest.getDisplayName(), internalName, health.getVersion());
            return;
        }

        var result = downloadPlugin(internalName, versionOverride);
        if (result) {
            //verifiy hash inside loadSidePlugin doesn't work
            loadSideLoadPlugin(internalName);
            sendPluginInstallTelemetry(manifest, versionOverride);
        }

        log.info("Added plugin {} to installed list", manifest.getDisplayName());
        eventBus.post(new ExternalPluginsChanged());
    }

    /**
     * Removes a plugin by removing it from the installed plugins list in config.
     *
     * @param manifest the manifest of the plugin to remove
     */
    public void remove(MicrobotPluginManifest manifest) {
        if (manifest == null || !manifestMap.containsValue(manifest)) {
            log.error("Can't install plugin: unable to identify manifest");
            return;
        }

        final String internalName = manifest.getInternalName();
        if (internalName == null || internalName.isEmpty()) {
            log.error("Cannot install plugin: internal name is null or empty");
            return;
        }

        blockedPlugins.remove(internalName);
        File jar = getPluginJarFile(internalName);
        URLClassLoader jarLoader = loaders.get(internalName);
        var pluginToRemove = pluginManager.getPlugins().stream()
                .filter(x -> x.getClass().getSimpleName().equalsIgnoreCase(internalName))
                .filter(x -> jarLoader != null && x.getClass().getClassLoader() == jarLoader)
                .findFirst();
        if (pluginToRemove.isPresent()) {
            // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
            // 上游原本在这里内联了一整段「停插件 + 从插件管理器摘掉 + 关 ClassLoader」的代码。
            // 本地把它抽成了 unloadSideLoadPlugin(internalName)，和全量重载复用同一套逻辑，
            // 避免两份实现走偏 —— 尤其是「ClassLoader 一定要关掉」这条不变量。
            //
            // 行为与上游等价：上游是 loaders.remove -> stopPlugin -> pluginManager.remove -> cl.close()，
            // unloadSideLoadPlugin 做的也是这四步，顺序一致。
            // 唯一差别在异常处理：上游这段在非 EDT 线程上会用 invokeAndWait，
            // 失败时把异常包成 RuntimeException 往上抛（会中断后面的删 jar 文件流程）；
            // 本地改成记一条 warn 日志后继续，不影响后续流程。
            //
            // 冲突时如何覆盖：保留这一行调用，把上游那一大段内联代码删掉。
            // 若上游重构了这段，优先保留本地的 unloadSideLoadPlugin 调用，
            // 并把 unloadSideLoadPlugin 里的四步顺序与上游对齐。
            // ===================== [MICROBOT 本地改动 END] =====================
            unloadSideLoadPlugin(internalName);
        } else {
            log.warn("Plugin to remove not found in plugin manager: {}", internalName);
        }

        if (jar.exists() && !jar.delete()) {
            log.warn("Failed to delete plugin jar {}", jar.getAbsolutePath());
        }
        clearInstalledPluginVersion(internalName);

        log.info("Removed plugin {} from installed list", manifest.getDisplayName());
        eventBus.post(new ExternalPluginsChanged());
    }

    private void sendPluginInstallTelemetry(MicrobotPluginManifest manifest, @Nullable String versionOverride)
    {
        if (manifest == null) {
            return;
        }

        String version = Strings.isNullOrEmpty(versionOverride) ? manifest.getVersion() : versionOverride;
        microbotApi.increasePluginInstall(manifest.getInternalName(), manifest.getDisplayName(), version);
    }

    /**
     * Updates a plugin by redownloading and reloading it.
     *
     * @param manifest
     */
    public void update(MicrobotPluginManifest manifest, @Nullable String versionOverride) {
        // remove and download the new one
        remove(manifest);
        install(manifest, versionOverride);
    }

    /**
     * @return
     * @apiNote
     */
    public List<Plugin> getInstalledPlugins() {
        Predicate<Plugin> isExternalPluginPredicate = plugin ->
                plugin.getClass().getAnnotation(PluginDescriptor.class).isExternal();

        List<Plugin> loadedPlugins = pluginManager.getPlugins()
                .stream()
                .filter(plugin -> !plugin.getClass().getAnnotation(PluginDescriptor.class).hidden())
                .filter(isExternalPluginPredicate)
                .collect(Collectors.toList());

        return loadedPlugins;
    }

    /**
     * Submits a plugin refresh task to the executor.
     * This will reload plugins based on the current profile's installed plugins list.
     */
    public Future<?> updatePlugin(MicrobotPluginManifest manifest, @Nullable String versionOverride) {
        return executor.submit(() -> update(manifest, versionOverride));
    }

    /**
     * Gets the manifest for a given plugin, this pulls from the global manifest map.
     *
     * @param plugin the plugin to get the manifest for
     * @return the manifest for the plugin, or null if not found or not an external plugin
     */
    @Nullable
    private MicrobotPluginManifest getPluginManifest(Plugin plugin) {
        PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
        if (descriptor == null || !descriptor.isExternal()) {
            return null;
        }

        String internalName = plugin.getClass().getSimpleName();

        return manifestMap.get(internalName);
    }

    /**
     * Gracefully stops a plugin
     */
    private void stopPlugin(Plugin plugin) {
        String pluginName = plugin.getClass().getSimpleName();

        try {
            if (pluginManager.isPluginActive(plugin)) {
                if (SwingUtilities.isEventDispatchThread()) {
                    pluginManager.stopPlugin(plugin);
                    return;
                } else  {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            pluginManager.stopPlugin(plugin);
                        } catch (PluginInstantiationException e) {
                            log.warn("Error stopping plugin {}: {}", pluginName, e.getMessage());
                        }
                    });
                }
            }
            pluginManager.remove(plugin);
        } catch (Exception e) {
            log.warn("Error during plugin stop for {}: {}", pluginName, e.getMessage());
        }
    }

    /**
     * Gracefully shuts down the plugin manager and performs final cleanup.
     */
    private void shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            return;
        }

        log.info("Shutting down MicrobotPluginManager");

        try {
            List<Plugin> externalPlugins = pluginManager.getPlugins().stream()
                    .filter(plugin -> {
                        PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
                        return descriptor != null && descriptor.isExternal();
                    })
                    .collect(Collectors.toList());

            for (Plugin plugin : externalPlugins) {
                stopPlugin(plugin);
            }

            // ===================== [MICROBOT 本地改动] side-load 脚本全量重载 =====================
            // 清理运行时副本目录。上面的 stopPlugin 只停插件，不会关掉 side-load 的
            // ClassLoader，所以副本文件这时可能还被占着，关掉句柄才能删掉。
            //
            // 冲突时如何覆盖：保留这几行。
            // ===================== [MICROBOT 本地改动 END] =====================
            for (String internalName : new ArrayList<>(loaders.keySet())) {
                URLClassLoader loader = loaders.remove(internalName);
                if (loader != null) {
                    try {
                        loader.close();
                    } catch (Exception ignored) {
                    }
                }
                deleteRuntimeCopy(internalName);
            }
            cleanupRuntimeCopies();

            log.info("MicrobotPluginManager shutdown complete");
        } catch (Exception e) {
            log.error("Error during MicrobotPluginManager shutdown", e);
        }
    }

    private Optional<InstalledPluginVersion> lookupInstalledPluginVersion(String internalName) {
        if (Strings.isNullOrEmpty(internalName)) {
            return Optional.empty();
        }
        String key = INSTALLED_VERSION_KEY_PREFIX + internalName;
        String value = configManager.getConfiguration(INSTALLED_VERSION_GROUP, key);
        if (Strings.isNullOrEmpty(value)) {
            return Optional.empty();
        }

        String[] parts = value.split(":", 2);
        if (parts.length < 2 || Strings.isNullOrEmpty(parts[0]) || Strings.isNullOrEmpty(parts[1])) {
            return Optional.empty();
        }

        return Optional.of(new InstalledPluginVersion(parts[0], parts[1]));
    }

    public Optional<String> getInstalledPluginVersion(String internalName) {
        return lookupInstalledPluginVersion(internalName).map(InstalledPluginVersion::getVersion);
    }

    public Optional<OutdatedPluginUpdate> getOutdatedPluginUpdate(Plugin plugin) {
        MicrobotPluginManifest manifest = getPluginManifest(plugin);
        if (manifest == null) {
            return Optional.empty();
        }

        String internalName = manifest.getInternalName();
        String latestVersion = manifest.getVersion();
        String installedVersion = getInstalledPluginVersion(internalName)
                .orElseGet(() -> {
                    PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
                    return descriptor == null ? null : descriptor.version();
                });

        if (Strings.isNullOrEmpty(internalName)
                || Strings.isNullOrEmpty(installedVersion)
                || Strings.isNullOrEmpty(latestVersion)
                || latestVersion.equals(installedVersion)
                || getPluginHealth(manifest, latestVersion).isBlocking()) {
            return Optional.empty();
        }

        String notificationKey = UPDATE_NOTIFICATION_KEY_PREFIX + internalName;
        String notifiedVersion = configManager.getConfiguration(UPDATE_NOTIFICATION_GROUP, notificationKey);
        if (latestVersion.equals(notifiedVersion)) {
            return Optional.empty();
        }

        return Optional.of(new OutdatedPluginUpdate(
                internalName,
                Strings.isNullOrEmpty(manifest.getDisplayName()) ? internalName : manifest.getDisplayName(),
                installedVersion,
                latestVersion
        ));
    }

    public MicrobotPluginHealth getPluginHealth(MicrobotPluginManifest manifest, @Nullable String version) {
        return MicrobotPluginHealth.evaluate(manifest, version,
                Rs2UiHelper.isClientVersionCompatible(manifest.getMinClientVersion()));
    }

    public Map<String, String> getBlockedPlugins() {
        return Collections.unmodifiableMap(blockedPlugins);
    }

    public synchronized Map<String, String> takeBlockedPluginsToNotify() {
        Map<String, String> pending = new TreeMap<>();
        blockedPlugins.forEach((name, reason) -> {
            if (!reason.equals(notifiedBlockedPlugins.get(name))) {
                pending.put(name, reason);
            }
        });
        notifiedBlockedPlugins.putAll(pending);
        return pending;
    }

    @Nullable
    String getLoadBlockReason(String internalName, PluginDescriptor descriptor) {
        if (!Rs2UiHelper.isClientVersionCompatible(descriptor.minClientVersion())) {
            return "requires client " + descriptor.minClientVersion() + " or newer (current "
                    + RuneLiteProperties.getMicrobotVersion() + ")";
        }
        if (descriptor.disable()) {
            return "disabled upstream";
        }

        MicrobotPluginManifest manifest = manifestMap.get(internalName);
        if (manifest == null) {
            return null;
        }
        MicrobotPluginHealth health = getPluginHealth(manifest, descriptor.version());
        String reason = health.getReason();
        if (manifest.isDisable()) {
            return "disabled upstream" + (reason == null ? "" : ": " + reason);
        }
        if (!Strings.isNullOrEmpty(descriptor.version())
                && (health.getState() == MicrobotPluginHealth.State.BROKEN || health.isAffected(descriptor.version()))) {
            return "version " + descriptor.version() + " is confirmed broken upstream" + (reason == null ? "" : ": " + reason);
        }
        return null;
    }

    public void rememberOutdatedPluginUpdateNotification(OutdatedPluginUpdate outdatedPluginUpdate) {
        if (outdatedPluginUpdate == null || Strings.isNullOrEmpty(outdatedPluginUpdate.getInternalName())
                || Strings.isNullOrEmpty(outdatedPluginUpdate.getLatestVersion())) {
            return;
        }

        configManager.setConfiguration(
                UPDATE_NOTIFICATION_GROUP,
                UPDATE_NOTIFICATION_KEY_PREFIX + outdatedPluginUpdate.getInternalName(),
                outdatedPluginUpdate.getLatestVersion()
        );
    }

    private void rememberInstalledPluginVersion(String internalName, String version, String sha256) {
        if (Strings.isNullOrEmpty(internalName) || Strings.isNullOrEmpty(version) || Strings.isNullOrEmpty(sha256)) {
            return;
        }

        configManager.setConfiguration(
                INSTALLED_VERSION_GROUP,
                INSTALLED_VERSION_KEY_PREFIX + internalName,
                version + ":" + sha256
        );
    }

    private void clearInstalledPluginVersion(String internalName) {
        if (Strings.isNullOrEmpty(internalName)) {
            return;
        }
        configManager.unsetConfiguration(INSTALLED_VERSION_GROUP, INSTALLED_VERSION_KEY_PREFIX + internalName);
    }

    private static final class InstalledPluginVersion {
        private final String version;
        private final String sha256;

        private InstalledPluginVersion(String version, String sha256) {
            this.version = version;
            this.sha256 = sha256;
        }

        public String getVersion() {
            return version;
        }

        public String getSha256() {
            return sha256;
        }
    }

    public static final class OutdatedPluginUpdate {
        private final String internalName;
        private final String displayName;
        private final String installedVersion;
        private final String latestVersion;

        private OutdatedPluginUpdate(String internalName, String displayName, String installedVersion, String latestVersion) {
            this.internalName = internalName;
            this.displayName = displayName;
            this.installedVersion = installedVersion;
            this.latestVersion = latestVersion;
        }

        public String getInternalName() {
            return internalName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getInstalledVersion() {
            return installedVersion;
        }

        public String getLatestVersion() {
            return latestVersion;
        }
    }
}
