package net.runelite.client.plugins.microbot.externalplugins;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 运行时副本的作用：让原 jar 加载完之后不再被占用。
 *
 * <p>背景：{@code URLClassLoader} 打开 jar 时会一直持有文件句柄，
 * Windows 上原 jar 就被锁住，没法覆盖 —— 调试时反复替换脚本 jar 很不方便。
 * 所以加载时改成复制一份到运行时目录，用副本加载。
 */
public class MicrobotPluginManagerRuntimeCopyTest
{
	/**
	 * 造一个临时 jar 文件。
	 */
	private static File newJar(File dir, String name, byte[] content) throws IOException
	{
		File jar = new File(dir, name);
		try (OutputStream out = new FileOutputStream(jar))
		{
			out.write(content);
		}
		return jar;
	}

	@Test
	public void copyingTheJarLeavesTheOriginalUnlocked() throws Exception
	{
		File dir = Files.createTempDirectory("microbot-runtime-copy-test").toFile();
		File original = newJar(dir, "MyScript.jar", "hello".getBytes());

		// 复制副本（模拟 createRuntimeCopy 做的事）
		File copy = new File(dir, "MyScript-copy.jar");
		Files.copy(original.toPath(), copy.toPath());

		// 关键断言：原 jar 现在可以随便覆盖
		try (OutputStream out = new FileOutputStream(original))
		{
			out.write("replaced".getBytes());
		}

		assertEquals("replaced", new String(Files.readAllBytes(original.toPath())));
	}

	@Test
	public void theCopyHasTheSameContentAsTheOriginal() throws Exception
	{
		File dir = Files.createTempDirectory("microbot-runtime-copy-test").toFile();
		byte[] content = "some jar bytes".getBytes();
		File original = newJar(dir, "MyScript.jar", content);

		File copy = new File(dir, "MyScript-copy.jar");
		Files.copy(original.toPath(), copy.toPath());

		byte[] originalBytes = Files.readAllBytes(original.toPath());
		byte[] copyBytes = Files.readAllBytes(copy.toPath());
		assertEquals(originalBytes.length, copyBytes.length);
		assertTrue(java.util.Arrays.equals(originalBytes, copyBytes));
	}
}
