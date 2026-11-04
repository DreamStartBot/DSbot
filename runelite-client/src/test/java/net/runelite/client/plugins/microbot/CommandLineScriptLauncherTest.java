package net.runelite.client.plugins.microbot;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.externalplugins.MicrobotPluginManager;

import org.junit.Before;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * --script 命令行启动器的测试。
 */
public class CommandLineScriptLauncherTest
{
	/**
	 * 一个普通的 side-load 脚本。
	 */
	@PluginDescriptor(name = "Auto Banker", isExternal = true)
	private static class AutoBanker extends Plugin
	{
	}

	/**
	 * 名字带 HTML 前缀的脚本 —— 和真实脚本一样用的是 PluginDescriptor.Mocrosoft。
	 */
	@PluginDescriptor(name = PluginDescriptor.Mocrosoft + "Fishing", isExternal = true)
	private static class Fishing extends Plugin
	{
	}

	/**
	 * 另一个同名脚本，用来测「多个同名」。
	 */
	@PluginDescriptor(name = "Auto Banker", isExternal = true)
	private static class OtherAutoBanker extends Plugin
	{
	}

	private PluginManager pluginManager;
	private MicrobotPluginManager microbotPluginManager;

	@Before
	public void setUp() throws Exception
	{
		pluginManager = mock(PluginManager.class);
		microbotPluginManager = mock(MicrobotPluginManager.class);

		when(pluginManager.startPlugin(any(Plugin.class))).thenReturn(true);
	}

	/**
	 * 在 EDT 上跑启动器。
	 */
	private void launch(String scriptName) throws Exception
	{
		CommandLineScriptLauncher launcher =
			new CommandLineScriptLauncher(pluginManager, microbotPluginManager, scriptName);
		SwingUtilities.invokeAndWait(launcher::launch);
	}

	private void withSideLoadPlugins(Plugin... plugins)
	{
		when(microbotPluginManager.getSideLoadPlugins()).thenReturn(Arrays.asList(plugins));
	}

	/**
	 * 精确匹配能找到脚本并启动。
	 */
	@Test
	public void startsTheScriptWithTheMatchingName() throws Exception
	{
		withSideLoadPlugins(new AutoBanker(), new Fishing());

		launch("Auto Banker");

		verify(pluginManager).startPlugin(any(AutoBanker.class));
	}

	/**
	 * 名字匹配忽略大小写。
	 */
	@Test
	public void theNameMatchIgnoresCase() throws Exception
	{
		withSideLoadPlugins(new AutoBanker());

		launch("auto banker");

		verify(pluginManager).startPlugin(any(AutoBanker.class));
	}

	/**
	 * 名字带 HTML 前缀时，干净的显示名也能匹配上。
	 */
	@Test
	public void thePlainDisplayNameMatches() throws Exception
	{
		withSideLoadPlugins(new Fishing());

		launch("Fishing");

		verify(pluginManager).startPlugin(any(Fishing.class));
	}

	/**
	 * 注解里的原始名字（含 HTML 前缀）也能匹配上。
	 */
	@Test
	public void theRawDescriptorNameMatches() throws Exception
	{
		withSideLoadPlugins(new Fishing());

		launch(PluginDescriptor.Mocrosoft + "Fishing");

		verify(pluginManager).startPlugin(any(Fishing.class));
	}

	/**
	 * 找不到就什么都不启动。
	 */
	@Test
	public void doesNothingWhenTheNameIsNotFound() throws Exception
	{
		withSideLoadPlugins(new AutoBanker());

		launch("No Such Script");

		verify(pluginManager, never()).startPlugin(any(Plugin.class));
	}

	/**
	 * 部分匹配不算命中 —— 名字对不上就是找不到，不猜。
	 */
	@Test
	public void doesNotMatchOnAPartialName() throws Exception
	{
		withSideLoadPlugins(new AutoBanker());

		launch("Bank");

		verify(pluginManager, never()).startPlugin(any(Plugin.class));
	}

	/**
	 * 多个同名时一个都不启动。
	 */
	@Test
	public void doesNotStartAnythingWhenTheNameIsAmbiguous() throws Exception
	{
		withSideLoadPlugins(new AutoBanker(), new OtherAutoBanker());

		launch("Auto Banker");

		verify(pluginManager, never()).startPlugin(any(Plugin.class));
	}

	/**
	 * 没传 --script 时什么都不做。
	 */
	@Test
	public void doesNothingWithoutTheParameter() throws Exception
	{
		withSideLoadPlugins(new AutoBanker());

		launch(null);
		launch("   ");

		verify(pluginManager, never()).startPlugin(any(Plugin.class));
	}

	/**
	 * 脚本本来是禁用的：启动它，但配置最终恢复成禁用 —— 不写进配置。
	 */
	@Test
	public void theScriptIsNotEnabledPermanently() throws Exception
	{
		withSideLoadPlugins(new AutoBanker());
		when(pluginManager.isPluginEnabled(any(AutoBanker.class))).thenReturn(false);

		launch("Auto Banker");

		verify(pluginManager).startPlugin(any(AutoBanker.class));
		// 临时打开过，启完恢复
		verify(pluginManager).setPluginEnabled(any(AutoBanker.class), eq(true));
		verify(pluginManager).setPluginEnabled(any(AutoBanker.class), eq(false));
	}

	/**
	 * 脚本本来就是启用的：不该去动它的启用状态。
	 */
	@Test
	public void anAlreadyEnabledScriptKeepsItsState() throws Exception
	{
		withSideLoadPlugins(new AutoBanker());
		when(pluginManager.isPluginEnabled(any(AutoBanker.class))).thenReturn(true);

		launch("Auto Banker");

		verify(pluginManager).startPlugin(any(AutoBanker.class));
		verify(pluginManager, never()).setPluginEnabled(any(Plugin.class), anyBoolean());
	}

	/**
	 * 只有 side-load 的脚本参与匹配 —— 内置插件不算。
	 */
	@Test
	public void onlySideLoadScriptsAreConsidered() throws Exception
	{
		when(microbotPluginManager.getSideLoadPlugins()).thenReturn(Collections.emptyList());

		launch("Auto Banker");

		verify(pluginManager, never()).startPlugin(any(Plugin.class));
	}

	/**
	 * 匹配到的东西按名字精确到唯一那个。
	 */
	@Test
	public void findMatchesReturnsOnlyTheMatchingOnes() throws Exception
	{
		withSideLoadPlugins(new AutoBanker(), new Fishing());

		CommandLineScriptLauncher launcher =
			new CommandLineScriptLauncher(pluginManager, microbotPluginManager, "Fishing");
		List<Plugin> matched = launcher.findMatches("Fishing");

		assertEquals(1, matched.size());
		assertTrue(matched.get(0) instanceof Fishing);
	}

	/**
	 * 启动器本身不碰配置 —— 启用状态的临时修改由它自己负责恢复。
	 */
	@Test
	public void theEnabledStateIsRestoredExactlyOnce() throws Exception
	{
		withSideLoadPlugins(new AutoBanker());
		when(pluginManager.isPluginEnabled(any(AutoBanker.class))).thenReturn(false);

		launch("Auto Banker");

		verify(pluginManager, times(1)).setPluginEnabled(any(AutoBanker.class), eq(true));
		verify(pluginManager, times(1)).setPluginEnabled(any(AutoBanker.class), eq(false));
	}
}
