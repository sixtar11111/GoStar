package local.starccm.assistant;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.openide.windows.Mode;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;
import star.common.Simulation;
import star.common.SimulationManager;

/** Dockable STAR window. The selected simulation stays pinned until explicitly changed. */
public final class AssistantTopComponent extends TopComponent {
    private static AssistantTopComponent instance;
    private final JComboBox<String> choices = new JComboBox<>();
    private final List<Simulation> simulations = new ArrayList<>();
    private final JPanel body = new JPanel(new BorderLayout());
    private final JLabel connected = new JLabel("未连接仿真");
    private CodexAssistant assistant;
    private Simulation target;
    private String assistantHome;
    private boolean refreshing;
    private final javax.swing.Timer connectionTimer = new javax.swing.Timer(750, e -> checkTarget());

    public AssistantTopComponent() {
        setName("GoStar");
        setDisplayName("GoStar");
        setLayout(new BorderLayout());
        assistantHome = System.getenv("STARCCM_ASSISTANT_HOME");
        if (assistantHome == null || assistantHome.isBlank()) {
            Properties defaults = new Properties();
            try (InputStream input = getClass().getResourceAsStream("installation.properties")) {
                if (input != null) defaults.load(input);
            } catch (java.io.IOException ignored) { /* User can select the project folder. */ }
            assistantHome = java.util.prefs.Preferences.userNodeForPackage(AssistantTopComponent.class)
                .get("assistant.home", defaults.getProperty("assistant.home", ""));
        }
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refresh = new JButton("刷新仿真列表");
        JButton connect = new JButton("连接所选仿真");
        JButton library = new JButton("资料目录…");
        choices.setPrototypeDisplayValue("Simulation — /path/to/case.sim");
        bar.add(choices);
        javax.swing.JPopupMenu targetMenu=new javax.swing.JPopupMenu();
        for(JButton original:new JButton[]{refresh,connect,library}) {
            javax.swing.JMenuItem item=new javax.swing.JMenuItem(original.getText());item.addActionListener(e -> original.doClick());targetMenu.add(item);
        }
        JButton manage=new JButton("仿真 ⋯");manage.addActionListener(e -> targetMenu.show(manage,0,manage.getHeight()));bar.add(manage);
        bar.add(connected);
        add(bar, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        placeholder();
        refresh.addActionListener(e -> refreshSimulations());
        choices.addActionListener(e -> { if (!refreshing) connectSelected(); });
        connect.addActionListener(e -> connectSelected());
        library.addActionListener(e -> selectLibrary());
    }

    public static Action openAction() {
        return new AbstractAction("GoStar") {
            @Override public void actionPerformed(ActionEvent event) { openAssistant(); }
        };
    }

    public static void openAssistant() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(AssistantTopComponent::openAssistant);
            return;
        }
        if (instance == null) instance = new AssistantTopComponent();
        if (!instance.isOpened()) {
            Mode mode = WindowManager.getDefault().findMode("editor");
            if (mode != null) mode.dockInto(instance);
            instance.open();
        }
        instance.requestActive();
    }

    @Override protected String preferredID() { return "GoStarAssistant"; }
    @Override public int getPersistenceType() { return TopComponent.PERSISTENCE_NEVER; }
    @Override protected void componentOpened() {
        connectionTimer.start();
        refreshSimulations();
        if (simulations.size() == 1) connectSelected();
    }
    @Override protected void componentClosed() {
        connectionTimer.stop();
        if (assistant != null) assistant.disposeEmbeddedPanel();
        assistant = null;
        target = null;
        connected.setText("未连接仿真");
        placeholder();
    }

    private void placeholder() {
        body.removeAll();
        body.add(new JLabel("  请先在 STAR 中打开 .sim，再刷新列表并连接目标仿真。"), BorderLayout.CENTER);
        body.revalidate();
        body.repaint();
    }

    private static boolean available(Simulation sim) {
        try { return sim != null && sim.isConnected() && sim.isValid(); }
        catch (RuntimeException disconnected) { return false; }
    }

    private void checkTarget() {
        if (target != null && !available(target)) {
            // Invalidate the old request generation before offering a new target.
            CodexAssistant previous = assistant;
            assistant = null;
            target = null;
            if (previous != null) previous.disposeEmbeddedPanel();
            connected.setText("原仿真已断开，请连接新仿真");
            setToolTipText("未连接仿真；不会向新文件延续旧请求");
            placeholder();
            refreshSimulations();
        }
    }

    private void refreshSimulations() {
        checkTarget();
        refreshing = true;
        try {
            simulations.clear();
            choices.removeAllItems();
            for (Simulation sim : SimulationManager.getSingleton().simulations()) {
                if (!available(sim)) continue;
                try {
                    String label = sim.getPresentationName() + " — " + sim.getSessionPath();
                    simulations.add(sim);
                    choices.addItem(label);
                } catch (RuntimeException disconnected) { /* Closed during enumeration. */ }
            }
            if (target != null && simulations.contains(target)) choices.setSelectedIndex(simulations.indexOf(target));
        } catch (RuntimeException error) { connected.setText("仿真列表暂不可用，请刷新"); }
        finally { refreshing = false; }
    }

    private void connectSelected() {
        checkTarget();
        int index = choices.getSelectedIndex();
        if (index < 0 || index >= simulations.size()) return;
        Simulation selected = simulations.get(index);
        if (!available(selected)) { refreshSimulations(); return; }
        if (selected == target) return;
        if (assistant != null && assistant.isRequestInProgress()) {
            refreshing = true;
            try { choices.setSelectedIndex(simulations.indexOf(target)); }
            finally { refreshing = false; }
            JOptionPane.showMessageDialog(this, "请等待当前请求完成，或先点击取消，再切换仿真。");
            return;
        }
        CodexAssistant next = new CodexAssistant();
        try {
            JPanel content = next.createEmbeddedPanel(selected, assistantHome);
            if (assistant != null) assistant.disposeEmbeddedPanel();
            assistant = next;
            target = selected;
            body.removeAll();
            body.add(content, BorderLayout.CENTER);
            connected.setText("当前目标：" + selected.getPresentationName());
            setToolTipText("修改仅作用于：" + selected.getSessionPath());
            body.revalidate();
            body.repaint();
        } catch (RuntimeException error) {
            next.disposeEmbeddedPanel();
            showError(error);
        }
    }

    private void selectLibrary() {
        if (assistant != null && assistant.isRequestInProgress()) {
            JOptionPane.showMessageDialog(this, "请等待当前请求完成，再更换资料目录。");
            return;
        }
        JFileChooser chooser = new JFileChooser(assistantHome.isBlank() ? "." : assistantHome);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择插件包的 runtime 目录（包含 scripts 和 star-ccm-skill）");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String selected = chooser.getSelectedFile().getAbsolutePath();
            if (!new java.io.File(selected, "scripts/starccm_skill_bridge.py").isFile()) {
                JOptionPane.showMessageDialog(this, "所选目录不含 scripts/starccm_skill_bridge.py。");
                return;
            }
            if (assistant != null) assistant.setAssistantHome(selected);
            assistantHome = selected;
            java.util.prefs.Preferences.userNodeForPackage(AssistantTopComponent.class).put("assistant.home", selected);
        }
    }

    private void showError(Exception error) {
        JOptionPane.showMessageDialog(this, error.getMessage(), "GoStar", JOptionPane.ERROR_MESSAGE);
    }
}
