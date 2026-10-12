package kz.smarthub.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Компьютерлік апп режимі: сервер қосылған соң (1) шағын бақылау терезесін көрсетеді,
 * (2) Edge/Chrome-ды "апп терезесі" (--app) режимінде ашады — мекенжай жолағы жоқ жеке терезе.
 * Дауыспен іздеу Edge/Chrome арқылы жұмыс істейді.
 */
@Component
@Profile("desktop")
public class DesktopLauncher {

    private static final Logger log = LoggerFactory.getLogger(DesktopLauncher.class);

    private final ConfigurableApplicationContext context;
    private final Environment env;

    public DesktopLauncher(ConfigurableApplicationContext context, Environment env) {
        this.context = context;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String url = "http://localhost:" + env.getProperty("local.server.port", "8765");
        log.info("Desktop режимі: {}", url);
        if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> showControlWindow(url));
        }
        openAppWindow(url);
    }

    private void showControlWindow(String url) {
        JFrame frame = new JFrame("Smart Service Hub");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        JLabel label = new JLabel("<html><div style='text-align:center'><b>Smart Service Hub</b> жұмыс істеп тұр<br>"
                + "работает<br><br>" + url + "</div></html>", SwingConstants.CENTER);
        JButton open = new JButton("Ашу / Открыть");
        JButton exit = new JButton("Шығу / Выход");
        open.addActionListener(e -> openAppWindow(url));
        exit.addActionListener(e -> shutdown(frame));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown(frame);
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        buttons.add(open);
        buttons.add(exit);
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
        root.add(label, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void shutdown(JFrame frame) {
        frame.dispose();
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }

    private void openAppWindow(String url) {
        for (String browser : browserCandidates()) {
            File file = new File(browser);
            if (file.exists()) {
                try {
                    new ProcessBuilder(browser, "--app=" + url, "--window-size=1200,820").start();
                    return;
                } catch (IOException e) {
                    log.warn("Браузерді ашу мүмкін болмады: {}", browser, e);
                }
            }
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            log.warn("Әдепкі браузерді ашу мүмкін болмады. Қолмен ашыңыз: {}", url, e);
        }
    }

    private List<String> browserCandidates() {
        List<String> list = new ArrayList<>();
        addIfSet(list, "ProgramFiles(x86)", "\\Microsoft\\Edge\\Application\\msedge.exe");
        addIfSet(list, "ProgramFiles", "\\Microsoft\\Edge\\Application\\msedge.exe");
        addIfSet(list, "ProgramFiles", "\\Google\\Chrome\\Application\\chrome.exe");
        addIfSet(list, "ProgramFiles(x86)", "\\Google\\Chrome\\Application\\chrome.exe");
        addIfSet(list, "LOCALAPPDATA", "\\Google\\Chrome\\Application\\chrome.exe");
        return list;
    }

    private void addIfSet(List<String> list, String envName, String suffix) {
        String base = System.getenv(envName);
        if (base != null && !base.isBlank()) {
            list.add(base + suffix);
        }
    }
}
