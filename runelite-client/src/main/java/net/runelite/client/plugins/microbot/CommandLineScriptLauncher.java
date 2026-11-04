package net.runelite.client.plugins.microbot;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.externalplugins.MicrobotPluginManager;
import net.runelite.client.plugins.microbot.util.PluginNames;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 按命令行 {@code --script} 指定的脚本名启动 side-load 脚本。
 *
 * <p>用法：{@code --script "Auto Banker"}
 *
 * <p>规则：
 * <ul>
 *   <li>只在 side-load 目录加载的 jar 脚本里找，客户端内置插件不算</li>
 *   <li>名字精确匹配、忽略大小写；干净的显示名和注解里的原始名都认</li>
 *   <li>找不到或者有多个同名，都不启动，只记一条 error 日志</li>
 *   <li>不写进配置：这次跑完，下次开客户端不会自动跑它</li>
 * </ul>
 *
 * <p>必须在 EDT 上调用 —— {@code PluginManager.startPlugin} 有这个断言。
 */
@Slf4j
public class CommandLineScriptLauncher
{
	private final PluginManager pluginManager;
	private final MicrobotPluginManager microbotPluginManager;
	private final String scriptName;

	@Inject
	public CommandLineScriptLauncher(
		PluginManager pluginManager,
		MicrobotPluginManager microbotPluginManager,
		// 没传 --script 时绑定给的是 null。Guice 只在参数标了 @Nullable 时才允许注入 null，
		// 不标的话它会拒绝创建实例 —— 那会让 MicrobotPlugin 整个加载失败。
		@Nullable @Named("scriptName") String scriptName)
	{
		this.pluginManager = pluginManager;
		this.microbotPluginManager = microbotPluginManager;
		this.scriptName = scriptName;
	}

	/**
	 * 启动 {@code --script} 指定的脚本。
	 *
	 * <p>没传 {@code --script} 时什么都不做。
	 */
	public void launch()
	{
		String wanted = scriptName == null ? "" : scriptName.trim();
		if (wanted.isEmpty())
		{
			return;
		}

		// PluginManager.startPlugin 断言必须在 EDT 上调用。
		// 正常路径是 MicrobotPlugin.startUp()，那本来就在 EDT 上；
		// 这里兜底，防止别的地方误调。
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::launch);
			return;
		}

		List<Plugin> matched = findMatches(wanted);

		if (matched.isEmpty())
		{
			log.error("--script 指定的脚本 \"{}\" 没找到。已加载的 side-load 脚本：{}",
				wanted, describeAvailable());
			return;
		}

		if (matched.size() > 1)
		{
			// 同名没法判断用户想要哪个，一个都不启。
			StringJoiner names = new StringJoiner(", ");
			for (Plugin plugin : matched)
			{
				names.add(PluginNames.displayName(plugin) + " (" + plugin.getClass().getName() + ")");
			}
			log.error("--script 指定的脚本 \"{}\" 有多个同名匹配，一个都没启动：{}",
				wanted, names);
			return;
		}

		Plugin target = matched.get(0);
		startWithoutPersisting(target);
	}

	/**
	 * 找出所有名字对得上的 side-load 脚本。
	 */
	List<Plugin> findMatches(String wanted)
	{
		List<Plugin> matched = new ArrayList<>();
		for (Plugin plugin : microbotPluginManager.getSideLoadPlugins())
		{
			if (PluginNames.matches(wanted, plugin))
			{
				matched.add(plugin);
			}
		}
		return matched;
	}

	/**
	 * 启动脚本，但不把它写进配置。
	 *
	 * <p>{@code PluginManager.startPlugin} 要求插件处于 enabled 状态，而
	 * {@code setPluginEnabled} 是会写配置的。所以这里先记下原状态，
	 * 必要时临时打开，启完再恢复 —— 配置最终保持原样，
	 * 下次开客户端不会自动跑这个脚本。
	 */
	private void startWithoutPersisting(Plugin target)
	{
		boolean wasEnabled = pluginManager.isPluginEnabled(target);

		if (!wasEnabled)
		{
			pluginManager.setPluginEnabled(target, true);
		}

		try
		{
			if (pluginManager.startPlugin(target))
			{
				log.info("已按 --script 启动脚本 {}", PluginNames.displayName(target));
			}
			else
			{
				log.info("脚本 {} 已经在运行或启动失败，跳过", PluginNames.displayName(target));
			}
		}
		catch (PluginInstantiationException e)
		{
			log.error("按 --script 启动脚本 {} 失败", PluginNames.displayName(target), e);
		}
		finally
		{
			if (!wasEnabled)
			{
				// 恢复原状，配置里不留「已启用」。
				pluginManager.setPluginEnabled(target, false);
			}
		}
	}

	/**
	 * 当前有哪些 side-load 脚本可选，用于「找不到」时的提示。
	 */
	private String describeAvailable()
	{
		List<Plugin> plugins = microbotPluginManager.getSideLoadPlugins();
		if (plugins.isEmpty())
		{
			return "（一个都没有）";
		}

		StringJoiner names = new StringJoiner(", ");
		for (Plugin plugin : plugins)
		{
			names.add(PluginNames.displayName(plugin));
		}
		return names.toString();
	}
}
