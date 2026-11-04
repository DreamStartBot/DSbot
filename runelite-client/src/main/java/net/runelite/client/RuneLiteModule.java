/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
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
package net.runelite.client;

import com.google.common.base.Strings;
import com.google.common.math.DoubleMath;
import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.binder.ConstantBindingBuilder;
import com.google.inject.name.Names;
import com.google.inject.util.Providers;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.AllArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.hooks.Callbacks;
import net.runelite.client.account.SessionManager;
import net.runelite.client.callback.Hooks;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ChatColorConfig;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.task.Scheduler;
import net.runelite.client.util.DeferredEventBus;
import net.runelite.client.util.ExecutorServiceExceptionLogger;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

@AllArgsConstructor
public class RuneLiteModule extends AbstractModule
{
	private final OkHttpClient okHttpClient;
	private final Supplier<Client> clientLoader;
	private final RuntimeConfigLoader configLoader;
	private final boolean developerMode;
	private final boolean safeMode;
	private final boolean disableTelemetry;
	private final boolean disableWalkerUpdate;
	private final File sessionfile;
	private final String profile;
	private final String character;
	// ===================== [MICROBOT 本地改动] Jagex 会话注入 =====================
	// 本地新增：从命令行传入 Jagex session id / character id / 角色名，
	// 用于在不经过 OAuth 登录流程的情况下把账号凭据注入游戏客户端（登录界面直接出现该角色的登录按钮）。
	// 与 profile / character 一样通过 Guice 的 @Named 绑定传给 RuneLite。
	// 详见 RuneLite.java 中标注 MICROBOT 本地改动 的位置。
	// ===================== [MICROBOT 本地改动 END] =====================
	private final String jagexSessionId;
	private final String jagexCharacterId;
	private final String jagexDisplayName;
	private final boolean insecureWriteCredentials;
	private final boolean noupdate;
	// ===================== [MICROBOT 本地改动] --index 自定义窗口标题 =====================
	// 本地新增：从命令行传入的窗口标题覆盖值（--index）。
	// 上游原本没有这个参数，窗口标题固定由 runelite.title 配置项 + 玩家名拼成。
	// 与 profile / character 一样通过 Guice 的 @Named 绑定传给 ClientUI。
	// 详见 ClientUI.java 中标注 MICROBOT 本地改动 的位置。
	// ===================== [MICROBOT 本地改动 END] =====================
	private final String windowTitleOverride;
	// ===================== [MICROBOT 本地改动] --script 启动指定脚本 =====================
	// 本地新增：命令行 --script 传入的脚本名。
	// 上游原本没有这个参数，脚本只能手动点工具栏的按钮启动。
	//
	// ⚠️ 这个类用 @AllArgsConstructor，字段顺序就是构造参数顺序。
	// 新字段必须放在最后 —— 挪位置会让所有 new RuneLiteModule(...) 的传参错位
	// （包括测试里的 PluginManagerTest）。
	//
	// 与 profile / character 一样通过 Guice 的 @Named 绑定传给启动器。
	// 详见 CommandLineScriptLauncher.java 中标注 MICROBOT 本地改动 的位置。
	// ===================== [MICROBOT 本地改动 END] =====================
	private final String scriptName;

	@Override
	protected void configure()
	{
		Properties properties = RuneLiteProperties.getProperties();
		Map<Object, Object> props = new HashMap<>(properties);

		RuntimeConfig runtimeConfig = configLoader.get();
		if (runtimeConfig != null && runtimeConfig.getProps() != null)
		{
			props.putAll(runtimeConfig.getProps());
		}

		// bind runelite.properties & runtime config
		for (Map.Entry<?, ?> entry : props.entrySet())
		{
			String key = (String) entry.getKey();
			if (entry.getValue() instanceof String)
			{
				ConstantBindingBuilder binder = bindConstant().annotatedWith(Names.named(key));
				binder.to((String) entry.getValue());
			}
			else if (entry.getValue() instanceof Double)
			{
				ConstantBindingBuilder binder = bindConstant().annotatedWith(Names.named(key));
				if (DoubleMath.isMathematicalInteger((double) entry.getValue()))
				{
					binder.to((int) (double) entry.getValue());
				}
				else
				{
					binder.to((double) entry.getValue());
				}
			}
			else if (entry.getValue() instanceof Boolean)
			{
				ConstantBindingBuilder binder = bindConstant().annotatedWith(Names.named(key));
				binder.to((boolean) entry.getValue());
			}
		}

		bindConstant().annotatedWith(Names.named("developerMode")).to(developerMode);
		bindConstant().annotatedWith(Names.named("safeMode")).to(safeMode);
		bindConstant().annotatedWith(Names.named("disableTelemetry")).to(disableTelemetry);
		bindConstant().annotatedWith(Names.named("disableWalkerUpdate")).to(disableWalkerUpdate);
		bind(File.class).annotatedWith(Names.named("sessionfile")).toInstance(sessionfile);
		bind(String.class).annotatedWith(Names.named("profile")).toProvider(Providers.of(profile));
		bind(String.class).annotatedWith(Names.named("character")).toProvider(Providers.of(character));
		// ===================== [MICROBOT 本地改动] Jagex 会话注入 =====================
		// 本地新增的三个绑定，供 RuneLite 注入命令行传入的 Jagex 凭据。
		// ===================== [MICROBOT 本地改动 END] =====================
		bind(String.class).annotatedWith(Names.named("jagexSessionId")).toProvider(Providers.of(jagexSessionId));
		bind(String.class).annotatedWith(Names.named("jagexCharacterId")).toProvider(Providers.of(jagexCharacterId));
		bind(String.class).annotatedWith(Names.named("jagexDisplayName")).toProvider(Providers.of(jagexDisplayName));
		bindConstant().annotatedWith(Names.named("insecureWriteCredentials")).to(insecureWriteCredentials);
		bindConstant().annotatedWith(Names.named("noupdate")).to(noupdate);
		// ===================== [MICROBOT 本地改动] --index 自定义窗口标题 =====================
		// 本地新增：把 --index 传入的标题字符串绑定给 ClientUI。
		// 上游更新后若此处冲突，保留这一行即可。
		// ===================== [MICROBOT 本地改动 END] =====================
		bindConstant().annotatedWith(Names.named("windowTitleOverride")).to(windowTitleOverride);
		// ===================== [MICROBOT 本地改动] --script 启动指定脚本 =====================
		// 本地新增：把 --script 传入的脚本名绑定给启动器。
		// 用 toProvider(Providers.of(...)) 而不是 bindConstant —— 后者不接受 null，
		// 而没传 --script 时这里就是 null。
		// 上游更新后若此处冲突，保留这一行即可。
		// ===================== [MICROBOT 本地改动 END] =====================
		bind(String.class).annotatedWith(Names.named("scriptName")).toProvider(Providers.of(scriptName));
		bind(File.class).annotatedWith(Names.named("runeLiteDir")).toInstance(RuneLite.RUNELITE_DIR);
		bind(ScheduledExecutorService.class).toInstance(new ExecutorServiceExceptionLogger(Executors.newSingleThreadScheduledExecutor()));
		bind(OkHttpClient.class).toInstance(okHttpClient);
		bind(RuntimeConfigLoader.class).toInstance(configLoader);
		bind(RuntimeConfigRefresher.class).asEagerSingleton();
		bind(MenuManager.class);
		bind(ChatMessageManager.class);
		bind(ItemManager.class);
		bind(Scheduler.class);
		bind(PluginManager.class);
		bind(SessionManager.class);

		bind(Gson.class).toInstance(RuneLiteAPI.GSON);

		bind(Callbacks.class).to(Hooks.class);

		bind(EventBus.class)
				.toInstance(new EventBus());

		bind(EventBus.class)
				.annotatedWith(Names.named("Deferred EventBus"))
				.to(DeferredEventBus.class);

		requestStaticInjection(Microbot.class);
	}

	@Provides
	@Singleton
	Client provideClient()
	{
		return clientLoader.get();
	}

	@Provides
	@Singleton
	RuntimeConfig provideRuntimeConfig()
	{
		return configLoader.get();
	}

	@Provides
	@Singleton
	RuneLiteConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneLiteConfig.class);
	}

	@Provides
	@Singleton
	ChatColorConfig provideChatColorConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChatColorConfig.class);
	}

	@Provides
	@Named("runelite.api.base")
	HttpUrl provideApiBase(@Named("runelite.api.base") String s)
	{
		final String prop = System.getProperty("runelite.http-service.url");
		return HttpUrl.get(Strings.isNullOrEmpty(prop) ? s : prop);
	}

	@Provides
	@Named("runelite.session")
	HttpUrl provideSession(@Named("runelite.session") String s)
	{
		final String prop = System.getProperty("runelite.session.url");
		return HttpUrl.get(Strings.isNullOrEmpty(prop) ? s : prop);
	}

	@Provides
	@Named("runelite.static.base")
	HttpUrl provideStaticBase(@Named("runelite.static.base") String s)
	{
		final String prop = System.getProperty("runelite.static.url");
		return HttpUrl.get(Strings.isNullOrEmpty(prop) ? s : prop);
	}

	@Provides
	@Named("runelite.ws")
	HttpUrl provideWs(@Named("runelite.ws") String s)
	{
		final String prop = System.getProperty("runelite.ws.url");
		return HttpUrl.get(Strings.isNullOrEmpty(prop) ? s : prop);
	}

	@Provides
	@Named("runelite.pluginhub.url")
	HttpUrl providePluginHubBase(@Named("runelite.pluginhub.url") String s)
	{
		return HttpUrl.get(System.getProperty("runelite.pluginhub.url", s));
	}

	@Provides
	@Singleton
	TelemetryClient provideTelemetry(
			OkHttpClient okHttpClient,
			Gson gson,
			@Named("runelite.api.base") HttpUrl apiBase)
	{
		return disableTelemetry ? null : new TelemetryClient(okHttpClient, gson, apiBase);
	}
}
