package net.runelite.client.plugins.microbot.util;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

/**
 * 插件名字的处理。
 *
 * <p>脚本的 {@code @PluginDescriptor} 里的 name 往往带 HTML 前缀，
 * 比如 {@code PluginDescriptor.Mocrosoft + "Auto Banker"} 实际是
 * {@code "<html>[<font color=#b8f704M>M</font>] Auto Banker"}。
 * 界面上显示的是去掉前缀之后的干净名字，命令行匹配时两种形式都认。
 */
public final class PluginNames
{
	private PluginNames()
	{
	}

	/**
	 * 插件的显示名。
	 *
	 * <p>去掉插件名里的标签和前缀方括号里的内容；没有名字就用类名。
	 *
	 * @param plugin 插件
	 * @return 显示名
	 */
	public static String displayName(Plugin plugin)
	{
		// 不走 Plugin.getName()：它在插件没有 @PluginDescriptor 注解时会直接抛 NPE。
		PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
		String raw = descriptor != null ? descriptor.name() : null;

		String name = stripTags(raw);
		return name.isEmpty() ? plugin.getClass().getSimpleName() : name;
	}

	/**
	 * 去掉插件名里的 HTML 标签和前缀方括号里的内容。
	 *
	 * @param raw 原始名字，可能为 null
	 * @return 去掉之后的干净名字
	 */
	public static String stripTags(String raw)
	{
		return Text.removeTags(raw == null ? "" : raw)
			.replaceFirst("^\\s*\\[[^\\]]*\\]", "")
			.trim();
	}

	/**
	 * 命令行传进来的名字能不能匹配上这个插件。
	 *
	 * <p>精确匹配，忽略大小写。两种形式都认：
	 * <ul>
	 *   <li>注解里的原始 name —— 带 {@code <html>}、{@code [M]} 那种前缀的</li>
	 *   <li>去掉前缀之后的干净显示名 —— 界面上看到的那个</li>
	 * </ul>
	 *
	 * <p>不做部分匹配。名字对不上就是找不到，不猜。
	 *
	 * @param wanted 命令行传入的名字
	 * @param plugin 插件
	 * @return 匹配返回 true
	 */
	public static boolean matches(String wanted, Plugin plugin)
	{
		String target = wanted == null ? "" : wanted.trim();
		if (target.isEmpty())
		{
			return false;
		}

		PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
		if (descriptor == null)
		{
			return false;
		}

		String raw = descriptor.name();
		if (raw != null && target.equalsIgnoreCase(raw.trim()))
		{
			return true;
		}

		return target.equalsIgnoreCase(displayName(plugin));
	}
}
