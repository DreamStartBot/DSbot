package net.runelite.client.plugins.microbot.toolbar;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 脚本启动弹窗的数据部分。
 *
 * <p>弹窗本身不好测，但筛选脚本和取显示名是纯逻辑，可以直接测。
 */
public class ScriptLauncherDialogTest
{
	/**
	 * 一个从 jar 加载的脚本。
	 */
	@PluginDescriptor(name = "A Bank Script", isExternal = true)
	private static class ExternalScript extends Plugin
	{
	}

	/**
	 * 一个隐藏的外部插件。
	 */
	@PluginDescriptor(name = "Hidden Script", isExternal = true, hidden = true)
	private static class HiddenExternalScript extends Plugin
	{
	}

	/**
	 * 一个客户端内置插件。
	 */
	@PluginDescriptor(name = "Community Plugins")
	private static class BuiltInPlugin extends Plugin
	{
	}

	/**
	 * 一个没有注解的插件。
	 */
	private static class NoDescriptorPlugin extends Plugin
	{
	}

	/**
	 * 名字里带标签和前缀方括号的插件。
	 */
	@PluginDescriptor(name = "[Beta] <col=ff9040>Tagged Script</col>", isExternal = true)
	private static class TaggedPlugin extends Plugin
	{
	}

	/**
	 * 注解里填满了各种信息的插件。
	 */
	@PluginDescriptor(name = "Full Info Script", description = "A script that does things",
		authors = {"Alice", "Bob"}, version = "2.3.4", tags = {"bank", "money"},
		isExternal = true)
	private static class FullInfoScript extends Plugin
	{
	}

	/**
	 * 一个描述里带标签和换行的插件。
	 */
	@PluginDescriptor(name = "Described Script", description = "<col=ff0000>Does</col> things\nand more",
		isExternal = true)
	private static class DescribedScript extends Plugin
	{
	}

	@Test
	public void picksOnlyExternalPlugins()
	{
		ExternalScript external = new ExternalScript();
		BuiltInPlugin builtIn = new BuiltInPlugin();

		PluginManager pluginManager = mock(PluginManager.class);
		when(pluginManager.getPlugins()).thenReturn(Arrays.asList(external, builtIn));

		List<Plugin> scripts = ScriptLauncherDialog.externalScripts(pluginManager);

		assertEquals(1, scripts.size());
		assertEquals(external, scripts.get(0));
	}

	@Test
	public void skipsHiddenExternalPlugins()
	{
		ExternalScript external = new ExternalScript();
		HiddenExternalScript hidden = new HiddenExternalScript();

		PluginManager pluginManager = mock(PluginManager.class);
		when(pluginManager.getPlugins()).thenReturn(Arrays.asList(external, hidden));

		List<Plugin> scripts = ScriptLauncherDialog.externalScripts(pluginManager);

		assertEquals(1, scripts.size());
		assertEquals(external, scripts.get(0));
	}

	@Test
	public void returnsEmptyListWithoutPluginManager()
	{
		assertTrue(ScriptLauncherDialog.externalScripts(null).isEmpty());
	}

	@Test
	public void usesThePluginNameAsDisplayName()
	{
		assertEquals("A Bank Script", ScriptLauncherDialog.displayName(new ExternalScript()));
	}

	@Test
	public void fallsBackToClassNameWhenThereIsNoName()
	{
		// 没有 @PluginDescriptor 的插件用类名
		NoDescriptorPlugin plugin = new NoDescriptorPlugin();
		assertEquals("NoDescriptorPlugin", ScriptLauncherDialog.displayName(plugin));
	}

	@Test
	public void stripsTagsAndBracketsFromTheName()
	{
		TaggedPlugin plugin = new TaggedPlugin();
		assertEquals("Tagged Script", ScriptLauncherDialog.displayName(plugin));
	}

	@Test
	public void readsTheDescriptionFromTheAnnotation()
	{
		assertEquals("A script that does things", ScriptLauncherDialog.description(new FullInfoScript()));
	}

	@Test
	public void stripsTagsAndNewlinesFromTheDescription()
	{
		// 标签去掉，换行压成空格
		assertEquals("Does things and more", ScriptLauncherDialog.description(new DescribedScript()));
	}

	@Test
	public void hasNoDescriptionWhenTheAnnotationDoesNotProvideOne()
	{
		assertEquals("", ScriptLauncherDialog.description(new ExternalScript()));
	}

	@Test
	public void readsTheVersionFromTheAnnotation()
	{
		assertEquals("2.3.4", ScriptLauncherDialog.version(new FullInfoScript()));
	}

	@Test
	public void readsTheAuthorsFromTheAnnotation()
	{
		assertEquals("Alice, Bob", ScriptLauncherDialog.authors(new FullInfoScript()));
	}

	@Test
	public void treatsTheDefaultAuthorAsNotFilledIn()
	{
		// authors 的默认值是 {"Unknown"}，那等于没填
		assertEquals("", ScriptLauncherDialog.authors(new ExternalScript()));
	}

	@Test
	public void readsTheTagsFromTheAnnotation()
	{
		assertEquals("bank, money", ScriptLauncherDialog.tags(new FullInfoScript()));
	}

	@Test
	public void hasNoTagsWhenTheAnnotationDoesNotProvideThem()
	{
		assertEquals("", ScriptLauncherDialog.tags(new ExternalScript()));
	}

	@Test
	public void buildsTheMetaLineFromVersionAuthorsAndTags()
	{
		assertEquals("v2.3.4  ·  by Alice, Bob  ·  bank, money",
			ScriptLauncherDialog.metaLine(new FullInfoScript()));
	}

	@Test
	public void skipsTheMetaPartsThatAreNotFilledIn()
	{
		// 只有版本，authors 和 tags 都没填
		assertEquals("v1.0.0", ScriptLauncherDialog.metaLine(new ExternalScript()));
	}

	@Test
	public void truncatesByDisplayWidth()
	{
		// 全角字符算两个宽度
		assertEquals("abc…", ScriptLauncherDialog.truncate("abcdef", 3));
		assertEquals("中文…", ScriptLauncherDialog.truncate("中文测试", 4));
	}

	@Test
	public void keepsTextThatFits()
	{
		assertEquals("abc", ScriptLauncherDialog.truncate("abc", 10));
		assertEquals("", ScriptLauncherDialog.truncate("", 10));
		assertEquals("", ScriptLauncherDialog.truncate(null, 10));
	}
}
