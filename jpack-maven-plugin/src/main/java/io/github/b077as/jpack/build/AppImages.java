package io.github.b077as.jpack.build;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Wraps the install folder into an AppImage with {@code appimagetool}. The stub is the AppImage's {@code AppRun}; because
 * it runs from a read-only mount, it updates by replacing the AppImage file (see the stub's {@code appimage} module).
 *
 * <p>Needs a Linux host (the file system must carry Unix permissions and symbolic links) and {@code appimagetool}.
 */
final class AppImages {

    private AppImages() {
    }

    /** The architecture name AppImage uses for a platform such as {@code linux-x64}. */
    static String architecture(String platform) {
        return platform.endsWith("arm64") ? "aarch64" : "x86_64";
    }

    /**
     * @param image      the install folder
     * @param executable the name of the stub inside it
     * @param icon       a PNG, or null for a plain placeholder
     * @param tool       the appimagetool executable (an AppImage itself is fine)
     * @return the AppImage that was written to {@code target}
     */
    static Path build(Path image, String executable, String appName, Path icon, Path tool, Path target, Path scratch, String platform,
                      Consumer<String> warn) throws IOException {
        if (Files.getFileAttributeView(image, PosixFileAttributeView.class) == null) {
            throw new IOException("an AppImage can only be built on Linux (this system has no Unix permissions)");
        }
        Path appDir = scratch.resolve("AppDir");
        Packager.deleteTree(appDir);
        Packager.copyTree(image, appDir);
        Files.createSymbolicLink(appDir.resolve("AppRun"), Path.of(executable));

        String desktop = "[Desktop Entry]\nType=Application\nName=" + appName + "\nExec=" + executable + "\nIcon=" + executable
                + "\nCategories=Utility;\nTerminal=false\n";
        Files.writeString(appDir.resolve(executable + ".desktop"), desktop, StandardCharsets.UTF_8);

        Path png = appDir.resolve(executable + ".png");
        if (icon != null && icon.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            Files.copy(icon, png, StandardCopyOption.REPLACE_EXISTING);
        } else {
            warn.accept("no PNG icon for the AppImage (put one at src/jpack/icon.png): a plain placeholder is used");
            placeholder(png);
        }

        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.deleteIfExists(target);
        ProcessBuilder builder = new ProcessBuilder(List.of(tool.toString(), "--appimage-extract-and-run", "--no-appstream", appDir.toString(), target.toString()));
        builder.environment().put("ARCH", architecture(platform));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("appimagetool did not finish");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while appimagetool ran", e);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(target)) {
            throw new IOException("appimagetool failed (" + process.exitValue() + "):\n" + output);
        }
        return target;
    }

    private static void placeholder(Path png) throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x4C6EF5));
        g.fillRoundRect(0, 0, 256, 256, 48, 48);
        g.dispose();
        ImageIO.write(image, "png", png.toFile());
    }
}
