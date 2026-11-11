package io.github.b077as.jpack.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How the app's programs introduce themselves on Windows: the icon, and the name the task manager, the taskbar and the
 * file properties show. Applies to the stub and to the copy of the Java launcher that runs the app, so that neither
 * appears as "Java Platform SE binary".
 *
 * <p>Only Windows executables have anything to brand; for other platforms every method does nothing.
 */
public final class Branding {

    /** An {@code .ico} file, or null to keep each program's own icon. */
    public Path icon;
    /** What the file properties and the task manager call the programs. Default: the app's name. */
    public String description;
    public String company;
    public String copyright;
    /** Default: the app's name. */
    public String product;

    /**
     * Gives {@code exe} the icon and the names. The file is edited in place.
     *
     * @param appName    the app's name
     * @param appVersion the version shown as the file and product version; blank shows 0.0.0
     * @param fileName   the name the program will have, such as {@code Komm.exe}
     */
    public void apply(Path exe, String appName, String appVersion, String fileName) throws IOException {
        // Windows shows no properties at all for a version block whose FileVersion is empty, so there is always one.
        final String version = appVersion == null || appVersion.isBlank() ? "0.0.0" : appVersion;
        Map<String, String> strings = new LinkedHashMap<>();
        strings.put("CompanyName", company == null ? "" : company);
        strings.put("FileDescription", description != null ? description : appName);
        strings.put("FileVersion", version);
        strings.put("InternalName", fileName.replaceAll("(?i)\\.exe$", ""));
        strings.put("LegalCopyright", copyright == null ? "" : copyright);
        strings.put("OriginalFilename", fileName);
        strings.put("ProductName", product != null ? product : appName);
        strings.put("ProductVersion", version);
        byte[] ico = icon == null ? null : Files.readAllBytes(icon);
        try {
            PeResources.edit(exe, root -> {
                try {
                    if (ico != null) {
                        PeResources.setIcon(root, ico);
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                PeResources.setVersion(root, version, strings);
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        } catch (IOException e) {
            throw new IOException("cannot brand " + exe.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Copies {@code source} to {@code target} and brands the copy. */
    public void applyCopy(Path source, Path target, String appName, String version) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        apply(target, appName, version, target.getFileName().toString());
    }
}
