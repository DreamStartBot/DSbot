package net.runelite.client.plugins.microbot;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * --script 命令行参数的解析测试。
 *
 * <p>验证 RuneLite.java 里取值的那个假设：{@code valuesOf} 按命令行里的
 * 出现顺序返回，传多次时最后一个就是命令行里最后写的那个。
 */
public class ScriptArgumentParsingTest
{
	private OptionParser parser;
	private ArgumentAcceptingOptionSpec<String> scriptOpt;

	private void setUpParser()
	{
		parser = new OptionParser();
		// 模拟真实情况：还有别的参数存在。
		parser.accepts("other");
		scriptOpt = parser.accepts("script", "Start the side-load script with this name.")
			.withRequiredArg()
			.ofType(String.class);
	}

	/**
	 * 没传 --script 时取不到值。
	 */
	@Test
	public void noValueWhenTheOptionIsMissing()
	{
		setUpParser();
		OptionSet options = parser.parse();

		assertFalse(options.has(scriptOpt));
	}

	/**
	 * {@code --script Foo} 这种写法。
	 */
	@Test
	public void readsTheValueWithASpace()
	{
		setUpParser();
		OptionSet options = parser.parse("--script", "Auto Banker");

		assertTrue(options.has(scriptOpt));
		List<String> values = options.valuesOf(scriptOpt);
		assertEquals(1, values.size());
		assertEquals("Auto Banker", values.get(0));
	}

	/**
	 * {@code --script=Foo} 这种写法。
	 */
	@Test
	public void readsTheValueWithAnEqualsSign()
	{
		setUpParser();
		OptionSet options = parser.parse("--script=Auto Banker");

		List<String> values = options.valuesOf(scriptOpt);
		assertEquals(1, values.size());
		assertEquals("Auto Banker", values.get(0));
	}

	/**
	 * 传多次时按出现顺序返回，最后一个就是命令行里最后写的那个 ——
	 * 「只认最后一个」靠的就是这个顺序。
	 */
	@Test
	public void keepsTheCommandLineOrder()
	{
		setUpParser();
		OptionSet options = parser.parse("--script", "First", "--script", "Second", "--script", "Third");

		List<String> values = options.valuesOf(scriptOpt);
		assertEquals(3, values.size());
		assertEquals("First", values.get(0));
		assertEquals("Second", values.get(1));
		assertEquals("Third", values.get(2));
		// RuneLite.java 里取的就是最后一个
		assertEquals("Third", values.get(values.size() - 1));
	}

	/**
	 * 名字里有空格也没问题 —— 加引号就行。
	 */
	@Test
	public void handlesANameWithSpaces()
	{
		setUpParser();
		OptionSet options = parser.parse("--script", "My Fancy Script");

		assertEquals("My Fancy Script", options.valuesOf(scriptOpt).get(0));
	}
}
