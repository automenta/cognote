package dumb.note.theme;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.*;
import javax.swing.plaf.metal.*;
import java.awt.*;
import java.util.Random;

/**
 * HackerDarkMetalLookAndFeel - A refined Metal Look and Feel with a cyberpunk-inspired
 * matte black theme, neon glow effects, and cohesive typography.
 */
public class DarkMetalLookAndFeel extends MetalLookAndFeel {

    // Refined neon color palette for text and accents
    private static final Color[] NEON_COLORS = {
            new Color(0, 255, 255),    // Cyan
            new Color(255, 0, 127),    // Hot Pink
            new Color(0, 255, 127),    // Neon Green
            new Color(127, 0, 255)     // Electric Purple
    };

    // Background colors with subtle gradients
    private static final Color MATTE_BLACK = new Color(10, 10, 10);
    private static final Color DARK_GRAY = new Color(25, 25, 25);
    private static final Color HIGHLIGHT_GRAY = new Color(45, 45, 45);
    private static final Color NEON_GLOW = new Color(0, 255, 255, 50); // Semi-transparent cyan glow

    // Typography
    private static final FontUIResource MONOSPACE_FONT =
            new FontUIResource("Monospace", Font.BOLD, 16);
    private static final FontUIResource TITLE_FONT =
            new FontUIResource("Monospace", Font.BOLD, 20);
    private static final FontUIResource SMALL_FONT =
            new FontUIResource("Monospace", Font.PLAIN, 12);

    /**
     * Constructor - creates our theme and installs it
     */
    public DarkMetalLookAndFeel() {
        MetalLookAndFeel.setCurrentTheme(new HackerDarkTheme());
    }

    @Override
    public String getName() {
        return "HackerDarkMetal";
    }

    @Override
    public String getID() {
        return "HackerDarkMetal";
    }

    @Override
    public String getDescription() {
        return "A cyberpunk-inspired dark metallic look and feel with neon glow accents";
    }

    /**
     * Custom Metal theme with enhanced aesthetics
     */
    private class HackerDarkTheme extends DefaultMetalTheme {

        @Override
        public String getName() {
            return "Hacker Dark";
        }

        @Override
        protected ColorUIResource getPrimary1() {
            return new ColorUIResource(50, 50, 50); // Subtle border
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return new ColorUIResource(35, 35, 35); // 3D effect
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return new ColorUIResource(MATTE_BLACK); // Component background
        }

        @Override
        public ColorUIResource getWindowBackground() {
            return new ColorUIResource(MATTE_BLACK); // Component background
        }

        @Override
        protected ColorUIResource getSecondary1() {
            return new ColorUIResource(15, 15, 15); // Dark shadow
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return new ColorUIResource(DARK_GRAY); // Mid shadow
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return new ColorUIResource(HIGHLIGHT_GRAY); // Light background
        }

        @Override
        public ColorUIResource getControlTextColor() {
            return new ColorUIResource(Color.LIGHT_GRAY);
        }

        @Override
        public ColorUIResource getMenuForeground() {
            return getControlTextColor();
        }

        @Override
        public ColorUIResource getMenuSelectedForeground() {
            return getControlHighlight();
        }

        @Override
        public ColorUIResource getSystemTextColor() {
            return getControlTextColor();
        }

        @Override
        public ColorUIResource getUserTextColor() {
            return getControlTextColor();
        }

        @Override
        public ColorUIResource getHighlightedTextColor() {
            return new ColorUIResource(Color.WHITE);
        }

        @Override
        public ColorUIResource getWindowTitleBackground() {
            return new ColorUIResource(new Color(5, 5, 5));
        }

        @Override
        public ColorUIResource getWindowTitleForeground() {
            return new ColorUIResource(NEON_COLORS[0]); // Consistent cyan for titles
        }

        @Override
        public ColorUIResource getMenuBackground() {
            return new ColorUIResource(DARK_GRAY);
        }

        @Override
        public ColorUIResource getControlHighlight() {
            return new ColorUIResource(HIGHLIGHT_GRAY);
        }

        @Override
        public FontUIResource getControlTextFont() {
            return MONOSPACE_FONT;
        }

        @Override
        public FontUIResource getSystemTextFont() {
            return MONOSPACE_FONT;
        }

        @Override
        public FontUIResource getUserTextFont() {
            return MONOSPACE_FONT;
        }

        @Override
        public FontUIResource getMenuTextFont() {
            return SMALL_FONT;
        }

        @Override
        public FontUIResource getWindowTitleFont() {
            return TITLE_FONT;
        }

        @Override
        public FontUIResource getSubTextFont() {
            return SMALL_FONT;
        }
    }

    /**
     * Custom button UI for neon glow effect
     */
    public static class HackerButtonUI extends MetalButtonUI {
        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = c.getWidth();
            int h = c.getHeight();

            // Gradient background
            GradientPaint gp = new GradientPaint(0, 0, DARK_GRAY, 0, h, MATTE_BLACK);
            g2d.setPaint(gp);
            g2d.fillRect(0, 0, w, h);

            // Neon glow on focus or hover
            if (b.getModel().isRollover() || b.hasFocus()) {
                g2d.setColor(NEON_GLOW);
                g2d.setStroke(new BasicStroke(3));
                g2d.drawRect(2, 2, w - 4, h - 4);
            }

            super.paint(g2d, c);
            g2d.dispose();
        }
    }

    @Override
    protected void initClassDefaults(UIDefaults table) {
        super.initClassDefaults(table);
        table.put("ButtonUI", "dumb.note.theme.DarkMetalLookAndFeel$HackerButtonUI");
    }

    @Override
    protected void initSystemColorDefaults(UIDefaults table) {
        super.initSystemColorDefaults(table);
        table.put("desktop", MATTE_BLACK);
        table.put("activeCaption", DARK_GRAY);
        table.put("inactiveCaption", MATTE_BLACK);
    }

    @Override
    protected void initComponentDefaults(UIDefaults table) {
        super.initComponentDefaults(table);

        // Refined borders with neon accents
        Border emptyBorder = new EmptyBorder(2, 2, 2, 2);



        Object[] defaults = {
                // Button styling
                "Button.margin", new InsetsUIResource(4, 8, 4, 8),
                "Button.background", DARK_GRAY,
                "Button.select", HIGHLIGHT_GRAY,

                // Text field styling
                "TextField.margin", new InsetsUIResource(3, 3, 3, 3),
                "TextField.caretBlinkRate", 400,
                "TextField.selectionBackground", new ColorUIResource(new Color(0, 100, 100)),
                "TextField.selectionForeground", new ColorUIResource(Color.WHITE),

                // Scrollbar styling
                "ScrollBar.width", 12,
                "ScrollBar.background", new ColorUIResource(MATTE_BLACK),
                "ScrollBar.thumbHighlight", new ColorUIResource(NEON_COLORS[2]), // Neon Green
                "ScrollBar.thumb", new ColorUIResource(DARK_GRAY),

                // Tables and lists
                "Table.gridColor", new ColorUIResource(new Color(40, 40, 40)),
                "Table.background", new ColorUIResource(MATTE_BLACK),
                "List.selectionBackground", new ColorUIResource(new Color(0, 80, 80)),

                // Menu styling
                "MenuItem.border", emptyBorder,
                "Menu.selectionBackground", new ColorUIResource(new Color(0, 80, 80)),

                // Focus and tooltip styling
                "Button.focus", new ColorUIResource(NEON_GLOW),
                "ToolTip.background", new ColorUIResource(new Color(20, 20, 20)),
                "ToolTip.border", new LineBorder(NEON_COLORS[0], 1),
                "ToolTip.foreground", new ColorUIResource(NEON_COLORS[0])
        };

        table.putDefaults(defaults);
    }
}

/**
 * Demo class to showcase the Look and Feel
 */
class DarkMetalLookAndFeelDemo {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(new DarkMetalLookAndFeel());

                JFrame frame = new JFrame("Hacker Dark Metal Look & Feel Demo");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

                JPanel content = new JPanel(new BorderLayout(10, 10));
                content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

                JLabel titleLabel = new JLabel("NEON CYBERSPACE");
                titleLabel.setHorizontalAlignment(JLabel.CENTER);
                content.add(titleLabel, BorderLayout.NORTH);

                JPanel centerPanel = new JPanel(new GridLayout(4, 1, 10, 10));
                centerPanel.add(new JTextField("Enter the grid..."));

                JButton button = new JButton("CONNECT");
                centerPanel.add(button);

                String[] listData = {"Cyberpunk 2077", "Neuromancer", "Snow Crash", "Altered Carbon"};
                centerPanel.add(new JComboBox<>(listData));

                JPanel buttonPanel = new JPanel(new FlowLayout());
                buttonPanel.add(new JButton("HACK"));
                buttonPanel.add(new JButton("DECRYPT"));
                buttonPanel.add(new JButton("INFILTRATE"));
                centerPanel.add(buttonPanel);

                content.add(centerPanel, BorderLayout.CENTER);

                JMenuBar menuBar = new JMenuBar();
                JMenu fileMenu = new JMenu("System");
                fileMenu.add(new JMenuItem("New Connection"));
                fileMenu.add(new JMenuItem("Open Terminal"));
                fileMenu.add(new JMenuItem("Shutdown"));
                menuBar.add(fileMenu);

                JMenu toolsMenu = new JMenu("Tools");
                toolsMenu.add(new JMenuItem("Scan"));
                toolsMenu.add(new JMenuItem("Decrypt"));
                toolsMenu.add(new JMenuItem("Trace"));
                menuBar.add(toolsMenu);

                frame.setJMenuBar(menuBar);
                frame.setContentPane(content);
                frame.setSize(500, 400);
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}