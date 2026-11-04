package net.runelite.client.plugins.microbot.util;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 插件名字处理的测试。
 */
public class PluginNamesTest
{
	@PluginDescriptor(name = "Auto Banker", isExternal = true)
	private static class AutoBanker extends Plugin
	{
	}

	@PluginDescriptor(name = PluginDescriptor.Mocrosoft + "Fishing", isExternal = true)
	private static class Fishing extends Plugin
	{
	}

	@PluginDescriptor(name = "  <html>[<font color=#b8f704M>M</font>] Spaced  ", isExternal = true)
	private static class Spaced extends Plugin
	{
	}

	/**
	 * 去掉 HTML 标签和方括号前缀。
	 */
	@Test
	public void stripsTheTagsAndThePrefix()
	{
		assertEquals("Fishing", PluginNames.displayName(new Fishing()));
		assertEquals("Spaced", PluginNames.displayName(new Spaced()));
		assertEquals("Auto Banker", PluginNames.displayName(new AutoBanker()));
	}

	/**
	 * 干净的显示名能匹配上。
	 */
	@Test
	public void matchesThePlainName()
	{
		assertTrue(PluginNames.matches("Fishing", new Fishing()));
		assertTrue(PluginNames.matches("Auto Banker", new AutoBanker()));
	}

	/**
	 * 注解里的原始名字也能匹配上。
	 */
	@Test
	public void matchesTheRawDescriptorName()
	{
		assertTrue(PluginNames.matches(PluginDescriptor.Mocrosoft + "Fishing", new Fishing()));
	}

	/**
	 * 匹配忽略大小写。
	 */
	@Test
	public void theMatchIgnoresCase()
	{
		assertTrue(PluginNames.matches("auto banker", new AutoBanker()));
		assertTrue(PluginNames.matches("AUTO BANKER", new AutoBanker()));
	}

	/**
	 * 部分匹配不算命中。
	 */
	@Test
	public void doesNotMatchOnAPartialName()
	{
		assertFalse(PluginNames.matches("Bank", new AutoBanker()));
		assertFalse(PluginNames.matches("Fishing", new AutoBanker()));
	}

	/**
	 * 空值不匹配任何东西。
	 */
	@Test
	public void emptyValuesMatchNothing()
	{
		assertFalse(PluginNames.matches(null, new AutoBanker()));
		assertFalse(PluginNames.matches("", new AutoBanker()));
		assertFalse(PluginNames.matches("   ", new AutoBanker()));
	}

	/**
	 * 两边的空格不影响匹配。
	 */
	@Test
	public void theSurroundingSpacesDoNotMatter()
	{
		assertTrue(PluginNames.matches("  Auto Banker  ", new AutoBanker()));
	}
}
