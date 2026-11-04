package net.runelite.client.plugins.microbot.externalplugins;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/**
 * 用来测试 side-load 卸载逻辑的插件。
 *
 * <p>必须是独立的顶层类：如果它是嵌套类，重新定义时会因为
 * {@code InnerClasses} 属性对不上而抛 {@code IncompatibleClassChangeError}。
 */
@PluginDescriptor(name = "Reload Fixture", isExternal = true)
public class ReloadFixturePlugin extends Plugin
{
}
