package de.rsflipper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.rsflipper.model.ClientSuggestion;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.VarClientStr;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

public class RSFlipperOverlayTest
{
	private int inputType;
	private String draft = "";
	private String title = "Set a price";
	private String displayed = "1,000 coins";
	private int quantity = 2;
	private OfferPrefill prefill;
	private RSFlipperOverlay overlay;

	@Before
	public void setUp()
	{
		Widget value = proxy(Widget.class, (method, args) ->
			"getText".equals(method) ? displayed : null);
		Widget setup = proxy(Widget.class, (method, args) -> {
			if ("getChild".equals(method))
			{
				int child = (int) args[0];
				if (child == 41)
				{
					return value;
				}
				return widget(null, new Rectangle(child, 10, 8, 8));
			}
			return null;
		});
		Client client = proxy(Client.class, (method, args) -> {
			switch (method)
			{
				case "getVarcIntValue": return inputType;
				case "getVarcStrValue":
					return args[0].equals(VarClientStr.INPUT_TEXT) ? draft : null;
				case "getVarpValue": return 4151;
				case "getVarbitValue":
					return (int) args[0] == 4396 ? quantity : 1;
				case "getWidget":
					if (args.length == 2)
					{
						return (int) args[0] == 465 && (int) args[1] == 26 ? setup : null;
					}
					if ((int) args[0] == ComponentID.CHATBOX_TITLE)
					{
						return widget(title, new Rectangle(0, 160, 300, 20));
					}
					if ((int) args[0] == ComponentID.CHATBOX_CONTAINER)
					{
						return widget(null, new Rectangle(0, 100, 300, 100));
					}
					return null;
				default: return null;
			}
		});
		RSFlipperConfig config = new RSFlipperConfig() {};
		SlotLocks locks = new SlotLocks(config, null);
		GameStateService state = new GameStateService(client, new Gson(), locks);
		JsonObject suggestion = new JsonObject();
		suggestion.addProperty("type", "buy");
		suggestion.addProperty("itemId", 4151);
		suggestion.addProperty("quantity", 2);
		suggestion.addProperty("price", 1000);
		state.setCurrentSuggestion(ClientSuggestion.from(suggestion));
		prefill = new OfferPrefill(client, null, state, config, null, null);
		overlay = new RSFlipperOverlay(client, state, config, prefill, null,
			new GeSearchSuggestion(client, state, config), locks);
	}

	@Test
	public void parsesCompletePositiveValuesIncludingLongMaximum()
	{
		String[] valid = {"1 coins", "1000 coins", "1,000 coins", "12,345 coins",
			"123,456 coins", "<col=ffff00>1,922,000 coins</col>",
			"2147483648 coins", "9,223,372,036,854,775,807 coins", "9223372036854775807 coins"};
		long[] expected = {1, 1000, 1000, 12345, 123456, 1922000, 2147483648L, Long.MAX_VALUE, Long.MAX_VALUE};
		for (int i = 0; i < valid.length; i++)
		{
			assertEquals(valid[i], expected[i], RSFlipperOverlay.parseSetupPriceText(valid[i]));
		}
	}

	@Test
	public void rejectsMalformedGroupingZeroOverflowAndAmbiguousText()
	{
		String[] invalid = {null, "", "0 coins", "0,000 coins", "01 coins", "1,00 coins",
			"1,,000 coins", "1,000, coins", "1,0000 coins", "1234,567 coins", ",100 coins",
			"1,234,56 coins", "1 000 coins", "1.000 coins", "2.8M coins", "-1 coins", "+5%",
			"9,223,372,036,854,775,808 coins", "9223372036854775808 coins", "1000 coins extra"};
		for (String text : invalid)
		{
			assertEquals(String.valueOf(text), -1L, RSFlipperOverlay.parseSetupPriceText(text));
		}
	}

	@Test
	public void confirmationRequiresClosedDialogEvenWhenAcceptedValuesMatch()
	{
		assertTrue("closed matching setup confirms", alphaAt(render(), 62, 14) > 0);
		for (int mode : new int[]{7, 30})
		{
			inputType = mode;
			for (String text : new String[]{"", "1000", "2000"})
			{
				draft = text;
				assertEquals("open dialog must not confirm: " + mode + "/" + text,
					0, alphaAt(render(), 62, 14));
			}
		}
		inputType = 0;
		draft = "2000";
		assertTrue("closed dialog ignores stale draft", alphaAt(render(), 62, 14) > 0);
	}

	@Test
	public void unknownValueSuppressesConfirmationAndPriceWarningButKeepsQuantityGuidance()
	{
		displayed = "1,,000 coins";
		assertEquals(0, alphaAt(render(), 62, 14));
		assertEquals(0, alphaAt(render(), 57, 14));
		quantity = 1;
		assertTrue(alphaAt(render(), 52, 14) > 0);
	}

	@Test
	public void acceptedMismatchHighlightsChangeInsteadOfConfirmation()
	{
		displayed = "2,000 coins";
		assertTrue(alphaAt(render(), 57, 14) > 0);
		assertEquals(0, alphaAt(render(), 65, 14));
	}

	@Test
	public void applyHintFollowsLiveDialogAndNeverAdvertisesTypedDraft()
	{
		inputType = 30;
		assertEquals(1000L, prefill.getCurrentInputValue());
		assertTrue("empty dialog shows apply hint", hasApplyHint(render()));
		draft = "2000";
		assertEquals("typed draft suppresses hint", false, hasApplyHint(render()));
		draft = "";
		inputType = 0;
		assertEquals(0L, prefill.getCurrentInputValue());
		assertEquals("closed dialog suppresses hint", false, hasApplyHint(render()));
		inputType = 7;
		title = "How many would you like to buy?";
		assertEquals(2L, prefill.getCurrentInputValue());
		assertTrue("quantity dialog still supported", hasApplyHint(render()));
	}

	private BufferedImage render()
	{
		BufferedImage image = new BufferedImage(400, 220, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			overlay.render(graphics);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private static int alphaAt(BufferedImage image, int x, int y)
	{
		return image.getRGB(x, y) >>> 24;
	}

	private static boolean hasApplyHint(BufferedImage image)
	{
		for (int y = 100; y < 200; y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if (alphaAt(image, x, y) > 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static Widget widget(String text, Rectangle bounds)
	{
		return proxy(Widget.class, (method, args) -> "getText".equals(method) ? text
			: "getBounds".equals(method) ? bounds : null);
	}

	private interface Stub
	{
		Object call(String method, Object[] args);
	}

	private static <T> T proxy(Class<T> type, Stub stub)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
			(instance, method, args) -> {
				Object value = stub.call(method.getName(), args);
				if (value == null && method.getReturnType() == boolean.class)
				{
					return false;
				}
				return value;
			}));
	}
}
