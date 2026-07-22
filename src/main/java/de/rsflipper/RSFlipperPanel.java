package de.rsflipper;

import de.rsflipper.model.ClientSuggestion;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Side-Panel (SPEC §4.6, M4): Suggestion in Klartext mit Risiko-Badge,
 * Skip/Block, Pause- und Sell-only-Toggle, Verbindungsstatus.
 */
public class RSFlipperPanel extends PluginPanel
{
	private final JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JLabel iconLabel = new JLabel();
	private final JLabel suggestionLabel = new JLabel(" ", SwingConstants.CENTER);
	// §4.6 v3 (Ramon): Aktions-Headline + Box-Hintergrund in der In-Game-Highlight-Farbe
	// FESTE Höhe (Ramon 2026-07-18): genug für Headline + Icon + Name/Profit/Dauer —
	// ändert sich nicht mit dem Vorschlags-Typ (kein Springen des Layouts bei Abort/Wait).
	// 112 -> 136 (Ramon 2026-07-20): Die ETA-v3-Leg-Zeile (buy/sell) kam dazu —
	// lange Item-Namen (2-3 Zeilen) + Profit + Est. time + Legs brauchen die Hoehe,
	// sonst wird der Inhalt an den Raendern beschnitten.
	private static final int SUGGESTION_BOX_HEIGHT = 136;
	private final JPanel suggestionRow = new JPanel(new BorderLayout(8, 2))
	{
		@Override
		public java.awt.Dimension getPreferredSize()
		{
			return new java.awt.Dimension(super.getPreferredSize().width, SUGGESTION_BOX_HEIGHT);
		}

		@Override
		public java.awt.Dimension getMaximumSize()
		{
			return new java.awt.Dimension(Integer.MAX_VALUE, SUGGESTION_BOX_HEIGHT);
		}
	};
	private final JLabel actionHeadline = new JLabel(" ", SwingConstants.CENTER);
	private final JButton skipButton = new JButton("Skip [+]");
	private final JButton blockButton = new JButton("Block");
	private final JToggleButton pauseToggle = new JToggleButton("Pause");
	private final JToggleButton sellOnlyToggle = new JToggleButton("Sell only");

	private volatile ClientSuggestion current;
	private final javax.swing.JComboBox<String> activityBox = new javax.swing.JComboBox<>(new String[]{"5 min", "30 min", "2 h", "8 h"});
	private final javax.swing.JTextField minProfitField = new javax.swing.JTextField();
	// Free-Tier-UI (Ramon 2026-07-19): Kontingent-Zeile + Opt-in + Reset-Timer + Tier-Label.
	private final JLabel quotaLabel = new JLabel(" ");
	private final javax.swing.JCheckBox useP2pBox = new javax.swing.JCheckBox("Use my free P2P suggestions");
	private final JLabel quotaTimerLabel = new JLabel(" ");
	private final JLabel buildLabel = new JLabel("Build: dev");
	private final JPanel quotaRow = new JPanel();
	private final JLabel tierTypeLabel = new JLabel(" ");
	private final JLabel tierCharsLabel = new JLabel(" ");
	private final JLabel tierSubLabel = new JLabel(" ");
	private final javax.swing.JButton getPremiumButton = new javax.swing.JButton("Get Premium");
	private long quotaResetMs = 0;
	private String popupShownForDay = "";
	private volatile boolean upsellLinkActive = false;
	private final javax.swing.JCheckBox passiveBox = new javax.swing.JCheckBox("Passive mode (observe only)");

	// §4.6: Mini-Preisgraph (5m-Serie high/low) — eigene Leichtgewicht-Komponente.
	private static final class PriceGraph extends javax.swing.JComponent
	{
		private volatile long[] high = new long[0];
		private volatile long[] low = new long[0];

		void setSeries(long[] high, long[] low)
		{
			this.high = high;
			this.low = low;
			repaint();
		}

		@Override
		protected void paintComponent(java.awt.Graphics g0)
		{
			java.awt.Graphics2D g = (java.awt.Graphics2D) g0;
			g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g.fillRect(0, 0, getWidth(), getHeight());
			long[] hi = high;
			long[] lo = low;
			long min = Long.MAX_VALUE;
			long max = Long.MIN_VALUE;
			for (long v : hi)
			{
				if (v > 0) { min = Math.min(min, v); max = Math.max(max, v); }
			}
			for (long v : lo)
			{
				if (v > 0) { min = Math.min(min, v); max = Math.max(max, v); }
			}
			if (min == Long.MAX_VALUE || max <= min)
			{
				return;
			}
			drawLine(g, hi, new Color(46, 204, 113), min, max);
			drawLine(g, lo, new Color(231, 76, 60), min, max);
		}

		private void drawLine(java.awt.Graphics2D g, long[] series, Color c, long min, long max)
		{
			g.setColor(c);
			int w = getWidth();
			int h = getHeight() - 4;
			int prevX = -1;
			int prevY = -1;
			for (int i = 0; i < series.length; i++)
			{
				if (series[i] <= 0) { prevX = -1; continue; }
				int x = (int) ((long) i * (w - 4) / Math.max(1, series.length - 1)) + 2;
				int y = 2 + (int) ((max - series[i]) * h / Math.max(1, max - min));
				if (prevX >= 0)
				{
					g.drawLine(prevX, prevY, x, y);
				}
				prevX = x;
				prevY = y;
			}
		}
	}

	private final PriceGraph priceGraph = new PriceGraph();
	private final JLabel whyLabel = new JLabel(" ");

	// §4.5/§4.6 UI v2: Profit-Sektion im Flip-Tab
	private final JLabel profitValue = new JLabel("--", SwingConstants.CENTER);
	private final javax.swing.JComboBox<String> periodCombo =
		new javax.swing.JComboBox<>(new String[]{"Session", "Today", "Week", "Month", "All time"});
	// §4.6 Session-Block (Ramon 2026-07-18): Flips, ROI, GP/h, Session-Timer
	private final JLabel statFlips = new JLabel("--", SwingConstants.RIGHT);
	private final JLabel statRoi = new JLabel("--", SwingConstants.RIGHT);
	private final JLabel statGph = new JLabel("--", SwingConstants.RIGHT);
	private final JLabel statSession = new JLabel("--", SwingConstants.RIGHT);
	private volatile long sessionStartMs = System.currentTimeMillis();

	public void setSessionStart(long ms)
	{
		this.sessionStartMs = ms;
	}

	private JPanel statRow(String caption, JLabel value)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 18));
		row.setAlignmentX(CENTER_ALIGNMENT);
		JLabel cap = new JLabel(caption);
		cap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		if (value.getForeground() == null || !value.getForeground().equals(new Color(46, 204, 113)))
		{
			value.setForeground(Color.WHITE);
		}
		row.add(cap, BorderLayout.WEST);
		row.add(value, BorderLayout.EAST);
		return row;
	}
	/** Liste, die die Viewport-Breite ERZWINGT — sonst rendert Swing sie in Wunschbreite
	 *  und die rechtsbündigen Profite ragen unsichtbar über den Rand (Ramon 2026-07-18). */
	private static final class ScrollableList extends JPanel implements javax.swing.Scrollable
	{
		@Override
		public java.awt.Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle r, int o, int d)
		{
			return 22;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle r, int o, int d)
		{
			return 110;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private final JPanel itemListPanel = new ScrollableList();
	// §4.6: Flip-Historie-Suche (Ramon 2026-07-18) — filtert live nach Item-Name.
	private final javax.swing.JTextField searchField = new javax.swing.JTextField();
	private volatile com.google.gson.JsonObject lastStats;
	private volatile Runnable statsRefresher;
	private volatile Runnable onResetSession;

	public void setStatsRefresher(Runnable r)
	{
		this.statsRefresher = r;
	}

	public void setOnResetSession(Runnable r)
	{
		this.onResetSession = r;
	}

	/** Gewählte Auswertungs-Periode (Session/Heute/Woche/Monat/Gesamt). */
	public String getSelectedPeriodLabel()
	{
		Object sel = periodCombo.getSelectedItem();
		return sel != null ? sel.toString() : "Session";
	}

	RSFlipperPanel(Consumer<ClientSuggestion> onSkip, Consumer<ClientSuggestion> onBlock, Runnable onToggleChanged,
		RSFlipperConfig config, net.runelite.client.config.ConfigManager configManager,
		de.rsflipper.api.AuthService auth, java.util.function.Supplier<String> serverUrl)
	{
		// wrap=false: RuneLite staucht gewrappte Panels auf ihre bevorzugte Höhe —
		// wir wollen die VOLLE Sidebar-Höhe (Item-Liste bis zum Fensterende, Ramon 2026-07-18).
		super(false);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Neues Logo (Claude-Design-Projekt, Ramon 2026-07-21): plugin-header.png
		// als Ressource — ersetzt das frueher gezeichnete Mark+Schriftzug-Logo.
		java.awt.image.BufferedImage headerImg =
			net.runelite.client.util.ImageUtil.loadImageResource(RSFlipperPanel.class, "header.png");
		JLabel title = new JLabel(new javax.swing.ImageIcon(
			headerImg.getScaledInstance(-1, 30, java.awt.Image.SCALE_SMOOTH)));
		title.setAlignmentX(CENTER_ALIGNMENT);

		statusLabel.setAlignmentX(CENTER_ALIGNMENT);
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		suggestionLabel.setAlignmentX(CENTER_ALIGNMENT);
		suggestionLabel.setForeground(Color.WHITE);
		// Kein eigener Rahmen mehr — die Box (suggestionRow) trägt Border + Tönung
		// (Ramon 2026-07-18: innere Linie war auf getöntem Grund als Kasten sichtbar).
		suggestionLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

		skipButton.addActionListener(e -> {
			ClientSuggestion s = current;
			if (s != null && s.isActionable())
			{
				onSkip.accept(s);
			}
		});
		blockButton.addActionListener(e -> {
			ClientSuggestion s = current;
			if (s != null && s.getItemId() > 0)
			{
				onBlock.accept(s);
			}
		});
		pauseToggle.addActionListener(e -> onToggleChanged.run());
		sellOnlyToggle.addActionListener(e -> onToggleChanged.run());

		JPanel actionRow = new JPanel(new GridLayout(1, 2, 6, 0));
		actionRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actionRow.add(skipButton);
		actionRow.add(blockButton);

		JPanel toggleRow = new JPanel(new GridLayout(1, 2, 6, 0));
		toggleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		toggleRow.add(pauseToggle);
		toggleRow.add(sellOnlyToggle);

		content.add(javax.swing.Box.createVerticalStrut(10)); // Luft nach oben (Ramon 2026-07-19)
		content.add(title);
		content.add(javax.swing.Box.createVerticalStrut(8));
		content.add(statusLabel);
		content.add(javax.swing.Box.createVerticalStrut(10));
		suggestionRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		suggestionRow.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
		iconLabel.setVerticalAlignment(SwingConstants.CENTER);
		actionHeadline.setFont(actionHeadline.getFont().deriveFont(Font.BOLD, 13f));
		suggestionRow.add(actionHeadline, BorderLayout.NORTH);
		suggestionRow.add(iconLabel, BorderLayout.WEST);
		suggestionRow.add(suggestionLabel, BorderLayout.CENTER);
		suggestionLabel.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (upsellLinkActive)
				{
					net.runelite.client.util.LinkBrowser.browse("https://rs-flipper.com/pricing");
				}
			}
		});
		content.add(suggestionRow);
		content.add(javax.swing.Box.createVerticalStrut(6));
		// ── Free-Tier-Kontingent (nur sichtbar fuer Free-User) ──
		quotaRow.setLayout(new BoxLayout(quotaRow, BoxLayout.Y_AXIS));
		quotaRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		quotaRow.setAlignmentX(CENTER_ALIGNMENT);
		quotaLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		quotaLabel.setFont(quotaLabel.getFont().deriveFont(java.awt.Font.BOLD, 13f));
		quotaLabel.setAlignmentX(CENTER_ALIGNMENT);
		quotaTimerLabel.setForeground(new java.awt.Color(255, 170, 0));
		quotaTimerLabel.setFont(quotaTimerLabel.getFont().deriveFont(13f));
		quotaTimerLabel.setAlignmentX(CENTER_ALIGNMENT);
		useP2pBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		useP2pBox.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		useP2pBox.setFont(useP2pBox.getFont().deriveFont(13f));
		useP2pBox.setAlignmentX(CENTER_ALIGNMENT);
		useP2pBox.setToolTipText("Unchecked: F2P suggestions only, your 10 daily members-item suggestions stay untouched.");
		useP2pBox.setSelected(config.useP2p());
		useP2pBox.addActionListener(e -> {
			configManager.setConfiguration(RSFlipperConfig.GROUP, "useP2p", useP2pBox.isSelected());
			onToggleChanged.run();
		});
		quotaRow.add(quotaLabel);
		quotaRow.add(useP2pBox);
		quotaRow.add(quotaTimerLabel);
		quotaRow.setVisible(false);
		content.add(quotaRow);
		content.add(javax.swing.Box.createVerticalStrut(4));
		new javax.swing.Timer(30_000, e -> updateQuotaTimer()).start();
		priceGraph.setPreferredSize(new java.awt.Dimension(200, 60));
		priceGraph.setMinimumSize(new java.awt.Dimension(100, 60));
		priceGraph.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 60));
		priceGraph.setAlignmentX(CENTER_ALIGNMENT);
		priceGraph.setToolTipText("5-min-Preisverlauf (~6 h): grün = Instabuy-Schnitt, rot = Instasell-Schnitt");
		content.add(priceGraph);
		content.add(javax.swing.Box.createVerticalStrut(8));
		content.add(actionRow);
		content.add(javax.swing.Box.createVerticalStrut(6));
		content.add(toggleRow);

		// ── Profit-Sektion (§4.6 UI v2 — Ramon 2026-07-18) ──
		JPanel profitHeader = new JPanel(new BorderLayout(0, 2));
		profitHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel profitCaption = new JLabel("Profit:", SwingConstants.CENTER);
		profitCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		profitValue.setFont(profitValue.getFont().deriveFont(Font.BOLD, 23f)); // +15% (Ramon)
		profitValue.setForeground(new Color(46, 204, 113));
		profitHeader.add(profitCaption, BorderLayout.NORTH);
		profitHeader.add(profitValue, BorderLayout.CENTER);

		JPanel periodRow = new JPanel(new BorderLayout(6, 0));
		periodRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		periodCombo.addActionListener(e -> {
			Runnable r = statsRefresher;
			if (r != null)
			{
				r.run();
			}
		});
		JButton resetSession = new JButton("Reset session");
		resetSession.setToolTipText("Restart session tracking");
		resetSession.addActionListener(e -> {
			Runnable r = onResetSession;
			if (r != null)
			{
				r.run();
			}
		});
		periodRow.add(periodCombo, BorderLayout.CENTER);
		periodRow.add(resetSession, BorderLayout.EAST);


		itemListPanel.setLayout(new BoxLayout(itemListPanel, BoxLayout.Y_AXIS));
		itemListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// RuneLites Scrollbalken überlagert den Inhalt — rechter Rand hält die Zahlen frei.
		itemListPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
		javax.swing.JScrollPane itemScroll = new javax.swing.JScrollPane(itemListPanel,
			javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
			javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		itemScroll.setBorder(BorderFactory.createEmptyBorder());
		itemScroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		itemScroll.getVerticalScrollBar().setUnitIncrement(12);
		itemScroll.getVerticalScrollBar().setPreferredSize(new java.awt.Dimension(8, 0)); // schmal
		itemScroll.setPreferredSize(new java.awt.Dimension(200, 300));
		itemScroll.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)); // füllt Resthöhe
		itemScroll.setAlignmentX(CENTER_ALIGNMENT);

		for (javax.swing.JComponent c : new javax.swing.JComponent[]{profitHeader, periodRow})
		{
			c.setAlignmentX(CENTER_ALIGNMENT);
			c.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + 8));
		}
		content.add(javax.swing.Box.createVerticalStrut(12));
		javax.swing.JSeparator sep = new javax.swing.JSeparator();
		sep.setForeground(ColorScheme.DARKER_GRAY_COLOR);
		content.add(sep);
		content.add(javax.swing.Box.createVerticalStrut(8));
		content.add(periodRow);
		content.add(javax.swing.Box.createVerticalStrut(6));
		content.add(profitHeader);
		content.add(javax.swing.Box.createVerticalStrut(6));
		content.add(statRow("Flips made:", statFlips));
		content.add(statRow("ROI:", statRoi));
		content.add(statRow("GP/h:", statGph));
		content.add(statRow("Session time:", statSession));
		content.add(javax.swing.Box.createVerticalStrut(6));
		searchField.setToolTipText("Search flips by item name");
		searchField.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 26));
		searchField.setAlignmentX(CENTER_ALIGNMENT);
		searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				renderItemList();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				renderItemList();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				renderItemList();
			}
		});
		content.add(searchField);
		content.add(javax.swing.Box.createVerticalStrut(4));
		content.add(itemScroll);

		// Session-Timer: grün, tickt sekündlich — nur sichtbar bei Betrachtung 'Session'.
		statSession.setForeground(new Color(46, 204, 113));
		new javax.swing.Timer(1000, e -> {
			if (!"Session".equals(getSelectedPeriodLabel()))
			{
				statSession.setText(" ");
				return;
			}
			if (sessionStartMs == 0)
			{
				statSession.setText("--"); // startet mit der ersten GE-Öffnung
				return;
			}
			long sec = Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 1000);
			statSession.setText(String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60));
		}).start();

		// ── Einstellungs-Tab (§4.6: eigener Tab statt im Flip-Tab) ──
		activityBox.setSelectedIndex(config.targetEtf().ordinal());
		activityBox.addActionListener(e -> {
			configManager.setConfiguration(RSFlipperConfig.GROUP, "targetEtf",
				RSFlipperConfig.Timeframe.values()[activityBox.getSelectedIndex()]);
			onToggleChanged.run();
		});
		minProfitField.setText(config.minProfitGp() > 0 ? String.valueOf(config.minProfitGp()) : "");
		minProfitField.setToolTipText("Hard floor in gp, e.g. 20000 or 20k (empty = off)");
		minProfitField.addFocusListener(new java.awt.event.FocusAdapter()
		{
			@Override
			public void focusLost(java.awt.event.FocusEvent e)
			{
				String raw = minProfitField.getText().trim().toLowerCase().replace(",", "");
				long v = 0;
				try
				{
					if (raw.endsWith("m"))
					{
						v = (long) (Double.parseDouble(raw.substring(0, raw.length() - 1)) * 1_000_000);
					}
					else if (raw.endsWith("k"))
					{
						v = (long) (Double.parseDouble(raw.substring(0, raw.length() - 1)) * 1_000);
					}
					else if (!raw.isEmpty())
					{
						v = Long.parseLong(raw);
					}
				}
				catch (NumberFormatException ignored)
				{
				}
				configManager.setConfiguration(RSFlipperConfig.GROUP, "minProfitGp", (int) Math.max(0, Math.min(Integer.MAX_VALUE, v)));
				minProfitField.setText(v > 0 ? String.valueOf(v) : "");
				onToggleChanged.run();
			}
		});
		passiveBox.setSelected(config.passiveMode());
		passiveBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		passiveBox.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		passiveBox.setToolTipText("Plugin keeps syncing (server logging) but never touches the game - for running alongside other flipping plugins.");
		passiveBox.addActionListener(e -> {
			configManager.setConfiguration(RSFlipperConfig.GROUP, "passiveMode", passiveBox.isSelected());
			onToggleChanged.run();
		});

		JPanel settingsTab = new JPanel();
		settingsTab.setLayout(new BoxLayout(settingsTab, BoxLayout.Y_AXIS));
		settingsTab.setBackground(ColorScheme.DARK_GRAY_COLOR);
		settingsTab.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 6));
		// ── M9: Account/Login (SPEC 12.1) ──
		settingsTab.add(buildAccountSection(auth, serverUrl, onToggleChanged));
		settingsTab.add(javax.swing.Box.createVerticalStrut(14));
		// Phase 3 (Ramon 2026-07-20): Sync-Schalter — LOKAL je Client, damit zwei
		// parallele Charaktere unterschiedliche Setups fahren koennen.
		final javax.swing.JCheckBox syncBox = new javax.swing.JCheckBox(
			"Sync settings across devices", config.syncSettings());
		syncBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		syncBox.setForeground(Color.WHITE);
		syncBox.setFont(syncBox.getFont().deriveFont(13f));
		syncBox.setAlignmentX(LEFT_ALIGNMENT);
		syncBox.setToolTipText("Off = this client keeps its own settings (e.g. different setups for two characters)");
		syncBox.addActionListener(e -> configManager.setConfiguration(
			RSFlipperConfig.GROUP, "syncSettings", syncBox.isSelected()));
		settingsTab.add(syncBox);
		settingsTab.add(javax.swing.Box.createVerticalStrut(10));

		JLabel activityCaption = new JLabel("Target time to fill:");
		JLabel minProfitCaption = new JLabel("Minimum profit (gp, e.g. 20k):");
		for (JLabel l : new JLabel[]{activityCaption})
		{
			l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			l.setAlignmentX(LEFT_ALIGNMENT);
		}
		for (javax.swing.JComponent c : new javax.swing.JComponent[]{activityBox, passiveBox})
		{
			c.setAlignmentX(LEFT_ALIGNMENT);
			c.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 28));
		}
		settingsTab.add(activityCaption);
		settingsTab.add(activityBox);
		settingsTab.add(javax.swing.Box.createVerticalStrut(10));
		minProfitCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		minProfitCaption.setAlignmentX(LEFT_ALIGNMENT);
		minProfitField.setAlignmentX(LEFT_ALIGNMENT);
		minProfitField.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 28));
		settingsTab.add(minProfitCaption);
		settingsTab.add(minProfitField);
		settingsTab.add(javax.swing.Box.createVerticalStrut(12));
		settingsTab.add(passiveBox);
		settingsTab.add(javax.swing.Box.createVerticalStrut(14));
		JLabel hotkeyCaption = new JLabel("Hotkeys (click, then press a key):");
		hotkeyCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hotkeyCaption.setAlignmentX(LEFT_ALIGNMENT);
		settingsTab.add(hotkeyCaption);
		settingsTab.add(javax.swing.Box.createVerticalStrut(4));
		settingsTab.add(hotkeyRow("Apply suggestion:", "fillHotkey", config.fillHotkey(), configManager));
		settingsTab.add(javax.swing.Box.createVerticalStrut(4));
		settingsTab.add(hotkeyRow("Skip suggestion:", "skipHotkey", config.skipHotkey(), configManager));

		// M12a Dump-Alerts (Ramon 2026-07-19): Opt-in-Schwelle, reservierte Slots, Sound.
		settingsTab.add(javax.swing.Box.createVerticalStrut(14));
		JLabel dumpCaption = new JLabel("Dump alerts:");
		dumpCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		dumpCaption.setAlignmentX(LEFT_ALIGNMENT);
		settingsTab.add(dumpCaption);
		// Premium-Verkaufsargument (Ramon 2026-07-20): Feature fuer Free SICHTBAR,
		// aber gesperrt — Hinweis-Label + deaktivierte Controls (Server gated ohnehin).
		dumpPremiumHint.setForeground(new java.awt.Color(255, 215, 80));
		dumpPremiumHint.setFont(dumpPremiumHint.getFont().deriveFont(12f));
		dumpPremiumHint.setAlignmentX(LEFT_ALIGNMENT);
		dumpPremiumHint.setVisible(false);
		settingsTab.add(dumpPremiumHint);
		settingsTab.add(javax.swing.Box.createVerticalStrut(4));
		final int[] dumpValues = {0, 100_000, 200_000, 500_000, 1_000_000, 2_000_000, 5_000_000};
		final javax.swing.JComboBox<String> dumpCombo = new javax.swing.JComboBox<>(
			new String[]{"Off", "100k+", "200k+", "500k+", "1M+", "2M+", "5M+"});
		this.dumpCombo = dumpCombo;
		int dumpSel = 0;
		for (int i = 0; i < dumpValues.length; i++)
		{
			if (dumpValues[i] == config.dumpMinProfit())
			{
				dumpSel = i;
			}
		}
		dumpCombo.setSelectedIndex(dumpSel);
		dumpCombo.setAlignmentX(LEFT_ALIGNMENT);
		dumpCombo.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 28));
		dumpCombo.addActionListener(e -> configManager.setConfiguration(
			RSFlipperConfig.GROUP, "dumpMinProfit", dumpValues[dumpCombo.getSelectedIndex()]));
		settingsTab.add(dumpCombo);
		settingsTab.add(javax.swing.Box.createVerticalStrut(6));
		final javax.swing.JCheckBox dumpSoundBox = new javax.swing.JCheckBox("Alert sound", config.dumpSound());
		this.dumpSoundBox = dumpSoundBox;
		dumpSoundBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		dumpSoundBox.setForeground(Color.WHITE);
		dumpSoundBox.setFont(dumpSoundBox.getFont().deriveFont(13f));
		dumpSoundBox.setAlignmentX(LEFT_ALIGNMENT);
		dumpSoundBox.addActionListener(e -> configManager.setConfiguration(
			RSFlipperConfig.GROUP, "dumpSound", dumpSoundBox.isSelected()));
		settingsTab.add(dumpSoundBox);
		// Debug-Simulate-Button entfernt (Ramon 2026-07-20, Launch-Vorbereitung) —
		// der Hook simulateDumpAlert bleibt im Plugin fuer kuenftige Dev-Zwecke.

		// Phase 4 (Ramon 2026-07-20): Feedback/Bug direkt aus dem Plugin.
		settingsTab.add(javax.swing.Box.createVerticalStrut(14));
		JButton feedbackButton = new JButton("Send feedback / report a bug");
		feedbackButton.setAlignmentX(LEFT_ALIGNMENT);
		feedbackButton.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 28));
		feedbackButton.addActionListener(e -> openFeedbackDialog());
		settingsTab.add(feedbackButton);

		// Phase 6 (Ramon 2026-07-20): Community-Server beitreten.
		settingsTab.add(javax.swing.Box.createVerticalStrut(6));
		JButton discordButton = new JButton("Join our Discord");
		discordButton.setAlignmentX(LEFT_ALIGNMENT);
		discordButton.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 28));
		discordButton.setBackground(new java.awt.Color(88, 101, 242));
		discordButton.setForeground(java.awt.Color.WHITE);
		discordButton.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse("https://discord.gg/F62tcCa9jS"));
		settingsTab.add(discordButton);
		// Build-Kennung (Ramon 2026-07-22): eindeutig pruefbar, welcher Stand laeuft.
		settingsTab.add(javax.swing.Box.createVerticalStrut(8));
		buildLabel.setForeground(new java.awt.Color(120, 130, 140));
		buildLabel.setFont(buildLabel.getFont().deriveFont(11f));
		buildLabel.setAlignmentX(LEFT_ALIGNMENT);
		settingsTab.add(buildLabel);

		javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
		tabs.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabs.addTab("Flip", content);
		tabs.addTab("Settings", settingsTab);
		add(tabs, BorderLayout.CENTER); // Liste darf bis zum Fensterende wachsen (Ramon 2026-07-18)

		// Stats laufend aktuell halten (30 s) — die Profit-Sektion ist jetzt immer sichtbar.
		new javax.swing.Timer(30_000, e -> {
			Runnable r = statsRefresher;
			if (r != null)
			{
				r.run();
			}
		}).start();
	}

	void setStatus(String text)
	{
		SwingUtilities.invokeLater(() -> statusLabel.setText("<html><center>" + text + "</center></html>"));
	}

	/** Build-Kennung im Settings-Footer (Ramon 2026-07-22). */
	void setBuildInfo(String build)
	{
		SwingUtilities.invokeLater(() -> buildLabel.setText("Build: " + build));
	}

	/** §4.6: Hotkey-Zeile mit Capture-Button — Klick, dann Taste drücken (ESC = abbrechen). */
	private JPanel hotkeyRow(String caption, String configKey, net.runelite.client.config.Keybind current,
		net.runelite.client.config.ConfigManager configManager)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 30));
		JLabel label = new JLabel(caption);
		label.setForeground(Color.WHITE);
		JButton btn = new JButton(keybindText(current));
		btn.setFocusable(true);
		// Feste Breite: die Pixelschrift liefert zu kleine Preferred-Sizes,
		// wodurch z.B. "ENTER" auf ein Zeichen abgeschnitten wurde.
		btn.setPreferredSize(new java.awt.Dimension(100, 26));
		btn.addActionListener(e -> {
			btn.setText("Press key...");
			btn.requestFocusInWindow();
		});
		btn.addKeyListener(new java.awt.event.KeyAdapter()
		{
			@Override
			public void keyPressed(java.awt.event.KeyEvent e)
			{
				if (!"Press key...".equals(btn.getText()))
				{
					return;
				}
				if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE)
				{
					btn.setText(keybindText(currentKeybind(configManager, configKey)));
					return;
				}
				net.runelite.client.config.Keybind kb =
					new net.runelite.client.config.Keybind(e.getKeyCode(), e.getModifiersEx());
				configManager.setConfiguration(RSFlipperConfig.GROUP, configKey, kb);
				btn.setText(keybindText(kb));
				e.consume();
			}
		});
		row.add(label, BorderLayout.CENTER);
		row.add(btn, BorderLayout.EAST);
		return row;
	}

	/** Keybind-Anzeige ASCII-sicher: die Pixelschrift kann macOS-Symbole (z.B. Return-Pfeil) nicht darstellen. */
	// Dump-Alert-Settings-Controls (Premium-Sperre, Ramon 2026-07-20).
	private javax.swing.JComboBox<String> dumpCombo;
	private javax.swing.JCheckBox dumpSoundBox;
	private final JLabel dumpPremiumHint = new JLabel("Premium feature - upgrade to unlock");

	/** Free-User sehen die Dump-Sektion, koennen sie aber nicht bedienen. */
	private void applyDumpPremiumLock(boolean premium)
	{
		if (dumpCombo != null)
		{
			dumpCombo.setEnabled(premium);
		}
		if (dumpSoundBox != null)
		{
			dumpSoundBox.setEnabled(premium);
		}
		dumpPremiumHint.setVisible(!premium);
	}

	/** M12a: rot pulsierender Rahmen des Suggestion-Felds bei aktivem Dump-Alert. */
	private javax.swing.Timer dumpPulseTimer;

	private void startDumpPulse()
	{
		if (dumpPulseTimer != null && dumpPulseTimer.isRunning())
		{
			return;
		}
		final long start = System.currentTimeMillis();
		dumpPulseTimer = new javax.swing.Timer(80, e -> {
			double t = ((System.currentTimeMillis() - start) % 900) / 900.0;
			float p = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * t)); // 0..1
			// Rahmen: dunkles Rot <-> knalliges Rot.
			Color red = new Color(231, 76, 60);
			Color dim = new Color(90, 30, 27);
			Color line = lerp(dim, red, p);
			// Hintergrund pulsiert dezent mit (dunkel genug, dass der Text lesbar bleibt).
			Color bgDark = new Color(48, 18, 16);
			Color bgBright = new Color(96, 32, 28);
			suggestionRow.setBackground(lerp(bgDark, bgBright, p));
			suggestionRow.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(line, 2, true),
				BorderFactory.createEmptyBorder(3, 6, 5, 6)));
		});
		dumpPulseTimer.start();
	}

	private void stopDumpPulse()
	{
		if (dumpPulseTimer != null)
		{
			dumpPulseTimer.stop();
			dumpPulseTimer = null;
		}
	}

	private static Color lerp(Color a, Color b, float p)
	{
		return new Color(
			(int) (a.getRed() + (b.getRed() - a.getRed()) * p),
			(int) (a.getGreen() + (b.getGreen() - a.getGreen()) * p),
			(int) (a.getBlue() + (b.getBlue() - a.getBlue()) * p));
	}

	/** Phase 4: Feedback-Dialog — Kategorie + Text, Versand via Plugin-Hook. */
	private java.util.function.BiConsumer<String, String> feedbackSender;

	public void setFeedbackSender(java.util.function.BiConsumer<String, String> sender)
	{
		this.feedbackSender = sender;
	}

	private void openFeedbackDialog()
	{
		javax.swing.JComboBox<String> cat = new javax.swing.JComboBox<>(new String[]{"Feedback / idea", "Bug report"});
		javax.swing.JTextArea text = new javax.swing.JTextArea(8, 28);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		javax.swing.JPanel form = new javax.swing.JPanel(new BorderLayout(0, 8));
		form.add(cat, BorderLayout.NORTH);
		form.add(new javax.swing.JScrollPane(text), BorderLayout.CENTER);
		int ok = javax.swing.JOptionPane.showConfirmDialog(null, form,
			"RS-Flipper - send feedback", javax.swing.JOptionPane.OK_CANCEL_OPTION,
			javax.swing.JOptionPane.PLAIN_MESSAGE);
		if (ok != javax.swing.JOptionPane.OK_OPTION)
		{
			return;
		}
		String msg = text.getText() != null ? text.getText().trim() : "";
		if (msg.length() < 5)
		{
			setStatus("Feedback too short - nothing sent");
			return;
		}
		java.util.function.BiConsumer<String, String> sender = feedbackSender;
		if (sender != null)
		{
			sender.accept(cat.getSelectedIndex() == 1 ? "bug" : "feedback", msg);
		}
	}

	/** Debug-Hook: loest im Plugin den simulierten Dump-Alert aus. */
	private volatile Runnable dumpSimulator;

	public void setDumpSimulator(Runnable r)
	{
		this.dumpSimulator = r;
	}

	/** ETA v3: Leg-Dauer kompakt formatieren (ASCII, RuneLite-Font). */
	private static String fmtLegEta(double min)
	{
		if (min > 240)
		{
			return ">4h";
		}
		return "~" + Math.max(1, Math.round(min)) + "m";
	}

	static String keybindText(net.runelite.client.config.Keybind kb)
	{
		if (kb == null || net.runelite.client.config.Keybind.NOT_SET.equals(kb))
		{
			return "Not set";
		}
		StringBuilder sb = new StringBuilder();
		int m = kb.getModifiers();
		if ((m & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0) sb.append("CTRL+");
		if ((m & java.awt.event.InputEvent.ALT_DOWN_MASK) != 0) sb.append("ALT+");
		if ((m & java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0) sb.append("SHIFT+");
		if ((m & java.awt.event.InputEvent.META_DOWN_MASK) != 0) sb.append("CMD+");
		String name;
		switch (kb.getKeyCode())
		{
			case java.awt.event.KeyEvent.VK_ENTER: name = "ENTER"; break;
			case java.awt.event.KeyEvent.VK_SPACE: name = "SPACE"; break;
			case java.awt.event.KeyEvent.VK_TAB: name = "TAB"; break;
			case java.awt.event.KeyEvent.VK_BACK_SPACE: name = "BACKSPACE"; break;
			case java.awt.event.KeyEvent.VK_ESCAPE: name = "ESC"; break;
			default:
				name = java.awt.event.KeyEvent.getKeyText(kb.getKeyCode());
				boolean ascii = !name.isEmpty();
				for (int i = 0; i < name.length(); i++)
				{
					if (name.charAt(i) > 126) { ascii = false; break; }
				}
				if (!ascii) { name = "KEY " + kb.getKeyCode(); }
				name = name.toUpperCase();
		}
		sb.append(name);
		return sb.toString();
	}

	private net.runelite.client.config.Keybind currentKeybind(net.runelite.client.config.ConfigManager cm, String key)
	{
		net.runelite.client.config.Keybind kb = cm.getConfiguration(RSFlipperConfig.GROUP, key, net.runelite.client.config.Keybind.class);
		return kb != null ? kb : net.runelite.client.config.Keybind.NOT_SET;
	}

	/** §4.5/§4.6 UI v2: Profit-Sektion rendern (große Zahl, Kennzahlen, Item-Liste). */
	void showStats(com.google.gson.JsonObject stats)
	{
		SwingUtilities.invokeLater(() -> {
			// Große Profit-Zahl der gewählten Periode.
			String sel = getSelectedPeriodLabel();
			String wanted = "Today".equals(sel) ? "tag" : "Week".equals(sel) ? "woche"
				: "Month".equals(sel) ? "monat" : "All time".equals(sel) ? "gesamt" : "session";
			long profit = 0;
			int flips = 0;
			long gph = 0;
			com.google.gson.JsonArray periods = stats.getAsJsonArray("periods");
			for (com.google.gson.JsonElement e : periods)
			{
				com.google.gson.JsonObject o = e.getAsJsonObject();
				if (o.get("label").getAsString().equals(wanted))
				{
					profit = o.get("profit").getAsLong();
					flips = o.get("flips").getAsInt();
					gph = o.get("gpProStunde").getAsLong();
					double roi = o.has("roiPct") ? o.get("roiPct").getAsDouble() : 0;
					statRoi.setText(String.format("%.2f%%", roi));
					break;
				}
			}
			profitValue.setText(RSFlipperOverlay.formatGp(profit));
			profitValue.setForeground(profit >= 0 ? new Color(46, 204, 113) : new Color(231, 76, 60));
			statFlips.setText(String.format("%,d", flips));
			statGph.setText(String.format("%,d gp/h", gph));

			// Item-Liste: separat gerendert (Such-Filter, Ramon 2026-07-18).
			lastStats = stats;
			renderItemList();
		});
	}

	/** Flip-Historie rendern — gefiltert nach dem Suchfeld (Name, case-insensitive). */
	private void renderItemList()
	{
		com.google.gson.JsonObject stats = lastStats;
		itemListPanel.removeAll();
		com.google.gson.JsonArray items = stats != null ? stats.getAsJsonArray("items") : null;
		String filter = searchField.getText().trim().toLowerCase();
		boolean any = false;
		boolean alt = false;
		if (items != null)
		{
			for (com.google.gson.JsonElement e : items)
			{
				com.google.gson.JsonObject o = e.getAsJsonObject();
				String fullName = o.get("name").isJsonNull() ? ("#" + o.get("itemId").getAsInt()) : o.get("name").getAsString();
				if (!filter.isEmpty() && !fullName.toLowerCase().contains(filter))
				{
					continue;
				}
				any = true;
				long p = o.get("profit").getAsLong();
				String name = fullName.length() > 22 ? fullName.substring(0, 21) + "…" : fullName;
				JPanel row = new JPanel(new BorderLayout(6, 0));
				row.setBackground(alt ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
				row.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 14)); // Platz für den Scrollbalken
				row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 22));
				JLabel left = new JLabel(String.format("%,d x %s", o.get("qty").getAsInt(), name));
				left.setForeground(Color.WHITE);
				JLabel right = new JLabel((p >= 0 ? "+" : "") + RSFlipperOverlay.formatGp(p));
				right.setForeground(p >= 0 ? new Color(46, 204, 113) : new Color(231, 76, 60));
				row.add(left, BorderLayout.CENTER);
				row.add(right, BorderLayout.EAST);
				if (o.has("closedTs") && !o.get("closedTs").isJsonNull())
				{
					row.setToolTipText(new java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.ENGLISH)
						.format(new java.util.Date(o.get("closedTs").getAsLong())));
				}
				itemListPanel.add(row);
				alt = !alt;
			}
		}
		if (!any)
		{
			JLabel empty = new JLabel(filter.isEmpty() ? "No flips in this period yet" : "No flips matching '" + filter + "'");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
			itemListPanel.add(empty);
		}
		itemListPanel.revalidate();
		itemListPanel.repaint();
	}

	void showSuggestion(ClientSuggestion s, javax.swing.Icon itemIcon)
	{
		current = s;
		SwingUtilities.invokeLater(() -> {
			// §4.6 v3: Headline + Box-Farbe = In-Game-Highlight-Farbe der Aktion.
			String type = s.getType() != null ? s.getType() : "wait";
			String headline;
			Color bg;
			switch (type)
			{
				case "buy": headline = "New Buy Order"; bg = new Color(26, 188, 156); break;      // türkis
				case "sell": headline = "New Sell Order"; bg = new Color(26, 188, 156); break;
				case "modify_buy": headline = "Change Buy Order"; bg = new Color(255, 152, 31); break; // orange
				case "modify_sell": headline = "Change Sell Order"; bg = new Color(255, 152, 31); break;
				case "abort": headline = "Abort Order"; bg = new Color(231, 76, 60); break;        // rot
				case "collect": headline = "Collect"; bg = new Color(46, 204, 113); break;         // grün
				default: headline = "Waiting"; bg = ColorScheme.DARKER_GRAY_COLOR; break;
			}
			// Pin-Vorschläge (Neu-Einstellen im Modify-Flow) bleiben ORANGE als Change Order —
			// der User modifiziert ja, auch wenn technisch neu platziert wird (Ramon 2026-07-18).
			if (s.isModifyFlow() && ("buy".equals(type) || "sell".equals(type)))
			{
				headline = "buy".equals(type) ? "Change Buy Order" : "Change Sell Order";
				bg = new Color(255, 152, 31);
			}
			// M12a Dump-Alert: eigene Headline + ROT (Ramon 2026-07-19), Feld pulsiert.
			if (s.isDumpAlert())
			{
				headline = "DUMP ALERT";
				bg = new Color(231, 76, 60);
			}
			boolean colored = !"wait".equals(type);
			// Sehr dunkle Tönung der Aktions-Farbe als Hintergrund (Ramon 2026-07-18):
			// ~16% Farbe auf dunklem Grund — Status sichtbar, Text bestens lesbar.
			Color base = ColorScheme.DARKER_GRAY_COLOR;
			Color tinted = colored ? new Color(
				(int) (bg.getRed() * 0.16 + base.getRed() * 0.84),
				(int) (bg.getGreen() * 0.16 + base.getGreen() * 0.84),
				(int) (bg.getBlue() * 0.16 + base.getBlue() * 0.84)) : base;
			suggestionRow.setBackground(tinted);
			suggestionRow.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(colored ? bg : ColorScheme.DARK_GRAY_COLOR, 2, true),
				BorderFactory.createEmptyBorder(3, 6, 5, 6)));
			// M12a: Dump-Alert-Feld pulsiert ROT (Ramon 2026-07-19).
			if (s.isDumpAlert())
			{
				startDumpPulse();
			}
			else
			{
				stopDumpPulse();
			}
			actionHeadline.setText(headline);
			// "DUMP ALERT" in Gold — knalliger Kontrast auf dem pulsierenden Rot.
			actionHeadline.setForeground(s.isDumpAlert() ? new Color(255, 215, 80)
				: (colored ? bg : ColorScheme.LIGHT_GRAY_COLOR));
			iconLabel.setIcon(itemIcon);

			// Inhalt: nur Item-Name, Profit, geschätzte Dauer (Ramon 2026-07-18).
			StringBuilder html = new StringBuilder("<html>");
			String textColor = "#e0e0e0";
			if (s.getItemName() != null)
			{
				// Stückzahl vor dem Namen (Ramon 2026-07-18): "2 x Antler guard"
				String qty = s.getQuantity() > 0
					&& !"abort".equals(type) && !"collect".equals(type)
					? String.format("%,d x ", s.getQuantity()) : "";
				html.append("<b><font color='").append(textColor).append("'>")
					.append(qty).append(s.getItemName()).append("</font></b><br>");
				// Collect nach Verkauf: realisierten Profit gross und farbig zeigen (Ramon 2026-07-19).
				if ("collect".equals(type) && s.getCollectProfit() != Long.MIN_VALUE)
				{
					long cp = s.getCollectProfit();
					String csign = cp >= 0 ? "+" : "-";
					String ccol = cp >= 0 ? "#2ecc71" : "#e74c3c";
					html.append("<font color='").append(ccol).append("'><b>").append(csign)
						.append(RSFlipperOverlay.formatGp(Math.abs(cp))).append(" gp</b></font><br>");
				}
				boolean showProfit = s.getExpectedProfit() != Long.MIN_VALUE
					&& ("buy".equals(type) || "sell".equals(type) || type.startsWith("modify"));
				if (showProfit)
				{
					String sign = s.getExpectedProfit() >= 0 ? "+" : "-"; // ASCII! RuneLite-Font kennt kein Unicode-Minus
					String pcol = s.getExpectedProfit() >= 0 ? "#2ecc71" : "#e74c3c";
					html.append("<font color='").append(pcol).append("'>Profit: ").append(sign)
						.append(RSFlipperOverlay.formatGp(Math.abs(s.getExpectedProfit()))).append("</font><br>");
				}
				if (s.isDumpAlert())
				{
					// Dumps kauft man instant zum gedumpten Preis — keine ETA, aber ein Hinweis.
					html.append("<font color='#ffd750'>Buy now - dump price</font>");
				}
				else if (s.getFillTimeMin() >= 0)
				{
					String eta = s.getFillTimeMin() > 240 ? ">4 h" : "~" + Math.max(1, Math.round(s.getFillTimeMin())) + " min";
					html.append("<font color='").append(textColor).append("'>Est. time: ").append(eta).append("</font>");
					// ETA v3 (Ramon 2026-07-20): Kauf-/Verkaufs-Leg ausweisen — gleiche
					// Schriftgroesse wie der Rest; auch wenn nur EIN Leg bekannt ist.
					com.google.gson.JsonObject why = s.getWhy();
					boolean hasBuyLeg = why != null && why.has("fillKaufMin");
					boolean hasSellLeg = why != null && why.has("fillVerkaufMin");
					if (hasBuyLeg || hasSellLeg)
					{
						StringBuilder legs = new StringBuilder();
						if (hasBuyLeg)
						{
							legs.append("buy ").append(fmtLegEta(why.get("fillKaufMin").getAsDouble()));
						}
						if (hasBuyLeg && hasSellLeg)
						{
							legs.append(" + ");
						}
						if (hasSellLeg)
						{
							legs.append("sell ").append(fmtLegEta(why.get("fillVerkaufMin").getAsDouble()));
						}
						html.append("<br><font color='#8b98a9'>").append(legs).append("</font>");
					}
				}
				if (s.isZeitGelockert())
				{
					// §4.6 v2: kein Item im gewählten Zeitfenster — ehrlicher Hinweis (Ramon).
					html.append("<br><font size='-2' color='#ff981f'>No match for your filters - closest ETA shown</font>");
				}
			}
			else
			{
				// Status ohne Item (wait/strukturell): Nachricht gut lesbar, mittig.
				String msg = s.getMessage() != null ? s.getMessage() : "\u2014";
				String upsell = "upgrade your account to unlock them.";
				upsellLinkActive = msg.contains(upsell);
				if (upsellLinkActive)
				{
					// Upsell-CTA farbig + unterstrichen, Klick oeffnet die Upgrade-Seite (Ramon 2026-07-19).
					msg = msg.replace(upsell,
						"<font color='#2ecc71'><u>" + upsell + "</u></font>");
				}
				html.append("<center><font color='").append(textColor).append("'>")
					.append(msg).append("</font></center>");
				suggestionLabel.setCursor(java.awt.Cursor.getPredefinedCursor(
					upsellLinkActive ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
			}
			html.append("</html>");
			suggestionLabel.setText(html.toString());
			suggestionLabel.setHorizontalAlignment(s.getItemName() == null ? SwingConstants.CENTER : SwingConstants.LEFT);

			boolean actionable = s.isActionable();
			skipButton.setEnabled(actionable && s.getItemId() > 0);
			blockButton.setEnabled(actionable && s.getItemId() > 0);
			priceGraph.setSeries(s.getGraphHigh(), s.getGraphLow());
			priceGraph.setVisible(s.getGraphHigh().length > 0);
			suggestionRow.revalidate(); // Höhe an neuen Inhalt anpassen
		});
	}

	boolean isPaused()
	{
		return pauseToggle.isSelected();
	}

	boolean isSellOnly()
	{
		return sellOnlyToggle.isSelected();
	}

	/** M9: Login-Bereich im Settings-Tab — E-Mail/Passwort gegen die RS-Flipper-API. */
	private JPanel buildAccountSection(de.rsflipper.api.AuthService auth,
		java.util.function.Supplier<String> serverUrl, Runnable onToggleChanged)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARK_GRAY_COLOR);
		box.setAlignmentX(LEFT_ALIGNMENT);

		JLabel caption = new JLabel("Account:");
		caption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		caption.setAlignmentX(LEFT_ALIGNMENT);
		JLabel status = new JLabel(" ");
		status.setAlignmentX(LEFT_ALIGNMENT);
		javax.swing.JTextField emailField = new javax.swing.JTextField();
		javax.swing.JPasswordField passwordField = new javax.swing.JPasswordField();
		javax.swing.JButton actionButton = new javax.swing.JButton();
		// Phase 6: Login ueber Discord (Device-Flow - oeffnet den Browser).
		javax.swing.JButton discordLoginButton = new javax.swing.JButton("Log in with Discord");
		discordLoginButton.setBackground(new java.awt.Color(88, 101, 242));
		discordLoginButton.setForeground(java.awt.Color.WHITE);
		for (javax.swing.JComponent c : new javax.swing.JComponent[]{emailField, passwordField, actionButton, discordLoginButton})
		{
			c.setAlignmentX(LEFT_ALIGNMENT);
			c.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 28));
		}

		Runnable render = new Runnable()
		{
			@Override
			public void run()
			{
				boolean in = auth.isLoggedIn();
				emailField.setVisible(!in);
				passwordField.setVisible(!in);
				discordLoginButton.setVisible(!in);
				actionButton.setText(in ? "Log out" : "Log in");
				status.setForeground(in ? new java.awt.Color(0, 200, 100) : ColorScheme.LIGHT_GRAY_COLOR);
				status.setText(in ? "Logged in as " + auth.email() : "Not logged in (rs-flipper.com account)");
				box.revalidate();
				box.repaint();
			}
		};
		actionButton.addActionListener(e -> {
			if (auth.isLoggedIn())
			{
				auth.logout();
				render.run();
				onToggleChanged.run();
				return;
			}
			String email = emailField.getText().trim();
			String pw = new String(passwordField.getPassword());
			if (email.isEmpty() || pw.isEmpty())
			{
				status.setText("Enter email and password");
				return;
			}
			actionButton.setEnabled(false);
			status.setText("Logging in ...");
			auth.login(serverUrl.get(), email, pw, err -> javax.swing.SwingUtilities.invokeLater(() -> {
				actionButton.setEnabled(true);
				if (err == null)
				{
					passwordField.setText("");
					render.run();
					onToggleChanged.run();
				}
				else
				{
					status.setForeground(new java.awt.Color(220, 80, 80));
					status.setText(err);
				}
			}));
		});
		passwordField.addActionListener(e -> actionButton.doClick());
		discordLoginButton.addActionListener(e -> {
			discordLoginButton.setEnabled(false);
			status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			status.setText("Waiting for Discord login in browser ...");
			auth.loginWithDiscord(serverUrl.get(), err -> javax.swing.SwingUtilities.invokeLater(() -> {
				discordLoginButton.setEnabled(true);
				if (err == null)
				{
					render.run();
					onToggleChanged.run();
				}
				else
				{
					status.setForeground(new java.awt.Color(220, 80, 80));
					status.setText(err);
				}
			}));
		});

		box.add(caption);
		box.add(javax.swing.Box.createVerticalStrut(4));
		box.add(status);
		for (JLabel l : new JLabel[]{tierTypeLabel, tierCharsLabel, tierSubLabel})
		{
			l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			l.setFont(l.getFont().deriveFont(13f));
			l.setAlignmentX(LEFT_ALIGNMENT);
		}
		getPremiumButton.setAlignmentX(LEFT_ALIGNMENT);
		getPremiumButton.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 28));
		getPremiumButton.setBackground(new java.awt.Color(16, 122, 87));
		getPremiumButton.setForeground(java.awt.Color.WHITE);
		getPremiumButton.setVisible(false);
		getPremiumButton.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse("https://rs-flipper.com/pricing"));
		box.add(javax.swing.Box.createVerticalStrut(6));
		box.add(tierTypeLabel);
		box.add(tierCharsLabel);
		box.add(tierSubLabel);
		box.add(javax.swing.Box.createVerticalStrut(6));
		box.add(getPremiumButton);
		box.add(javax.swing.Box.createVerticalStrut(6));
		box.add(emailField);
		box.add(javax.swing.Box.createVerticalStrut(4));
		box.add(passwordField);
		box.add(javax.swing.Box.createVerticalStrut(6));
		box.add(actionButton);
		box.add(javax.swing.Box.createVerticalStrut(4));
		box.add(discordLoginButton);
		render.run();
		return box;
	}

	/** Tier-/Kontingent-Anzeige aus der Sync-Antwort (Ramon 2026-07-19). */
	void setAccountInfo(com.google.gson.JsonObject account)
	{
		javax.swing.SwingUtilities.invokeLater(() -> {
			if (account == null)
			{
				tierTypeLabel.setText(" ");
				tierCharsLabel.setText(" ");
				tierSubLabel.setText(" ");
				getPremiumButton.setVisible(false);
				quotaRow.setVisible(false);
				return;
			}
			boolean premium = "premium".equals(account.get("tier").getAsString());
			boolean launchFree = account.has("launchFree") && !account.get("launchFree").isJsonNull()
				&& account.get("launchFree").getAsBoolean();
			int maxChars = account.has("maxChars") ? account.get("maxChars").getAsInt() : 1;
			// Free-Launch (Ramon 2026-07-20): alles freigeschaltet — ehrlich anzeigen.
			tierTypeLabel.setText("Type: " + (premium ? "Premium"
				: launchFree ? "Free (launch - all features unlocked)" : "Free"));
			tierCharsLabel.setText("Unlocked characters: " + (maxChars < 0 ? "unlimited" : String.valueOf(maxChars)));
			getPremiumButton.setVisible(!premium && !launchFree);
			// Launch: ALLE sind quasi Premium (Ramon 2026-07-20) — Dump-Sektion offen.
			applyDumpPremiumLock(premium || launchFree);
			if (premium)
			{
				String sub = account.has("subscription") && !account.get("subscription").isJsonNull()
					? account.get("subscription").getAsString() : "active";
				// Ab Stripe (M12) liefert der Server bei Kuendigung subscriptionEndsAt (YYYY-MM-DD).
				if ("canceled".equals(sub) && account.has("subscriptionEndsAt") && !account.get("subscriptionEndsAt").isJsonNull())
				{
					sub = "canceled (ends " + account.get("subscriptionEndsAt").getAsString() + ")";
				}
				tierSubLabel.setText("Subscription: " + sub);
				quotaRow.setVisible(false);
				return;
			}
			tierSubLabel.setText("Subscription: -");
			if (!account.has("p2p") || account.get("p2p").isJsonNull())
			{
				quotaRow.setVisible(false);
				return;
			}
			com.google.gson.JsonObject p2p = account.has("p2p") && account.get("p2p").isJsonObject()
				? account.getAsJsonObject("p2p") : null;
			if (p2p == null)
			{
				quotaRow.setVisible(false);
				return;
			}
			int used = p2p.get("used").getAsInt();
			int limit = p2p.get("limit").getAsInt();
			int left = Math.max(0, limit - used);
			quotaResetMs = p2p.get("resetsAtMs").getAsLong();
			quotaLabel.setText(String.format("P2P suggestions today: %d/%d", left, limit));
			boolean exhausted = left == 0;
			// Aufgebraucht => "0/10" in Rot (Ramon 2026-07-19).
			quotaLabel.setForeground(exhausted ? new java.awt.Color(231, 76, 60) : ColorScheme.LIGHT_GRAY_COLOR);
			quotaTimerLabel.setVisible(exhausted);
			updateQuotaTimer();
			quotaRow.setVisible(true);
			if (exhausted && useP2pBox.isSelected())
			{
				maybeShowPremiumPopup();
			}
		});
	}

	private void updateQuotaTimer()
	{
		if (!quotaTimerLabel.isVisible() || quotaResetMs <= 0)
		{
			return;
		}
		long ms = quotaResetMs - System.currentTimeMillis();
		if (ms <= 0)
		{
			quotaTimerLabel.setText("Quota resets any moment ...");
			return;
		}
		quotaTimerLabel.setText(String.format("Resets in %dh %02dm", ms / 3_600_000, (ms % 3_600_000) / 60_000));
	}

	/** Einmal pro Tag: Premium-Hinweis mit Call-to-Action zur Website. */
	private void maybeShowPremiumPopup()
	{
		String today = java.time.LocalDate.now().toString();
		if (today.equals(popupShownForDay))
		{
			return;
		}
		popupShownForDay = today;
		Object[] options = {"Get Premium", "Later"};
		// Mittig auf dem BILDSCHIRM (Ramon 2026-07-19) — mit Panel-Parent hing der
		// Dialog rechts an der Sidebar.
		int pick = javax.swing.JOptionPane.showOptionDialog(null,
			"You've used all 10 free P2P suggestions for today.\n"
				+ "You'll keep getting F2P suggestions until the quota resets.\n\n"
				+ "Premium: unlimited suggestions on all items and\n"
				+ "3 simultaneous characters - 4.99 EUR/month.",
			"Free quota used up",
			javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.INFORMATION_MESSAGE,
			null, options, options[0]);
		if (pick == javax.swing.JOptionPane.YES_OPTION)
		{
			net.runelite.client.util.LinkBrowser.browse("https://rs-flipper.com/pricing");
		}
	}
}
