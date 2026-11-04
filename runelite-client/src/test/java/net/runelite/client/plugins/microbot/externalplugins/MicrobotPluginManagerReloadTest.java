package net.runelite.client.plugins.microbot.externalplugins;

import net.runelite.client.plugins.Plugin;
import org.junit.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * side-load 脚本的卸载判定。
 *
 * <p>全量重载的核心是：只卸载真正由 side-load 的 ClassLoader 加载的脚本，
 * 不能因为名字碰巧相同就把别的插件也停掉。
 *
 * <p>判定用的是 ClassLoader，不是类名 —— 因为一个 jar 里可能装多个脚本，
 * jar 文件名和插件类名根本对不上。
 */
public class MicrobotPluginManagerReloadTest
{
	/**
	 * 造一个独立的 ClassLoader，用来模拟 side-load 的加载器。
	 *
	 * <p>不能直接用 {@code URLClassLoader.loadClass}：它是 parent-first 委托，
	 * 拿回来的类还是父加载器加载的，{@code getClassLoader()} 对不上。
	 * 这里自己定义这个类，才模拟得出 side-load 的情形。
	 */
	private static ClassLoader newLoader() throws Exception
	{
		Class<?> fixture = ReloadFixturePlugin.class;
		String resource = "/" + fixture.getName().replace('.', '/') + ".class";
		byte[] bytes;
		try (InputStream in = fixture.getResourceAsStream(resource))
		{
			bytes = in.readAllBytes();
		}

		return new ClassLoader(fixture.getClassLoader())
		{
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
			{
				if (!name.equals(fixture.getName()))
				{
					return super.loadClass(name, resolve);
				}

				Class<?> loaded = findLoadedClass(name);
				if (loaded == null)
				{
					loaded = defineClass(name, bytes, 0, bytes.length);
				}
				if (resolve)
				{
					resolveClass(loaded);
				}
				return loaded;
			}
		};
	}

	/**
	 * 用指定 ClassLoader 造一个插件实例。
	 */
	private static Plugin newPlugin(ClassLoader loader) throws Exception
	{
		Class<?> clazz = Class.forName(ReloadFixturePlugin.class.getName(), true, loader);
		return (Plugin) clazz.getDeclaredConstructor().newInstance();
	}

	@Test
	public void findsThePluginLoadedByTheGivenClassLoader() throws Exception
	{
		ClassLoader loader = newLoader();
		Plugin plugin = newPlugin(loader);

		assertSame("插件应该确实是由这个 loader 加载的", loader, plugin.getClass().getClassLoader());

		List<Plugin> found = MicrobotPluginManager.findSideLoadPlugins(
			Collections.singletonList(plugin), loader);

		assertEquals("应该能找到这个脚本", 1, found.size());
		assertSame(plugin, found.get(0));
	}

	@Test
	public void doesNotTouchASameNamedPluginFromAnotherClassLoader() throws Exception
	{
		ClassLoader loader = newLoader();
		// 这个插件是用测试类自己的 loader 加载的，不是 side-load 那个
		Plugin corePlugin = new ReloadFixturePlugin();

		List<Plugin> found = MicrobotPluginManager.findSideLoadPlugins(
			Collections.singletonList(corePlugin), loader);

		assertTrue("同名但来自别的 ClassLoader 的插件不能被当成 side-load 脚本", found.isEmpty());
	}

	/**
	 * 一个 jar 里装多个脚本时，卸载要能把这个 jar 的脚本全部卸掉。
	 *
	 * <p>这是踩过的坑：原先拿 jar 文件名去和插件类名比，一个 jar 装两个脚本时
	 * 一个都匹配不上，旧的插件就一直挂在插件管理器上，重载后变成重复条目。
	 */
	@Test
	public void findsEveryPluginFromTheSameJar() throws Exception
	{
		ClassLoader loader = newLoader();
		Plugin first = newPlugin(loader);
		Plugin second = newPlugin(loader);
		// 另一个 jar 的插件，不该被算进来
		Plugin other = new ReloadFixturePlugin();

		List<Plugin> found = MicrobotPluginManager.findSideLoadPlugins(
			Arrays.asList(first, second, other), loader);

		assertEquals("同一个 jar 的两个脚本都要找到", 2, found.size());
		assertTrue(found.contains(first));
		assertTrue(found.contains(second));
	}

	@Test
	public void ignoresPluginsWithADifferentClassLoader() throws Exception
	{
		ClassLoader loader = newLoader();
		Plugin plugin = newPlugin(loader);

		List<Plugin> found = MicrobotPluginManager.findSideLoadPlugins(
			Collections.singletonList(plugin), newLoader());

		assertTrue("不同的 loader 不该匹配", found.isEmpty());
	}

	@Test
	public void handlesANullClassLoader() throws Exception
	{
		List<Plugin> found = MicrobotPluginManager.findSideLoadPlugins(
			Collections.singletonList(new ReloadFixturePlugin()), null);

		assertTrue(found.isEmpty());
	}
}
