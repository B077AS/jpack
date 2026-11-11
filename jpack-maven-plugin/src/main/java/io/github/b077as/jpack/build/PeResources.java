package io.github.b077as.jpack.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Reads and replaces the resources (icon, version information, manifest) of a Windows executable, so that an app's
 * programs can carry the app's name and icon without an external tool.
 *
 * <p>The edited resource tree is written as a new last section and the old one is left behind unused. That works for every
 * executable whatever the order of its sections, and never has to move anything that other parts of the file point to.
 * An Authenticode signature, which an edit would invalidate anyway, is removed.
 */
final class PeResources {

    /** Resource types. */
    static final int RT_ICON = 3;
    static final int RT_GROUP_ICON = 14;
    static final int RT_VERSION = 16;

    /** One level of the resource tree: a directory of named and numbered entries, or a leaf holding data. */
    static final class Node {
        final Map<String, Node> named = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Map<Integer, Node> ids = new TreeMap<>();
        byte[] data;
        int codePage;

        Node child(int id) {
            return ids.computeIfAbsent(id, key -> new Node());
        }

        boolean isLeaf() {
            return data != null;
        }
    }

    private PeResources() {
    }

    /** Applies {@code edit} to the resource tree of {@code exe} and writes the result back to the same file. */
    static void edit(Path exe, Consumer<Node> edit) throws IOException {
        byte[] file = Files.readAllBytes(exe);
        Files.write(exe, edit(file, edit));
    }

    static byte[] edit(byte[] file, Consumer<Node> edit) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (file.length < 0x40 || in.getShort(0) != 0x5A4D) {
            throw new IOException("not a Windows executable (no MZ header)");
        }
        int pe = in.getInt(0x3C);
        if (pe < 0 || pe + 24 > file.length || in.getInt(pe) != 0x00004550) {
            throw new IOException("not a Windows executable (no PE signature)");
        }
        int sectionCount = in.getShort(pe + 6) & 0xFFFF;
        int optionalSize = in.getShort(pe + 20) & 0xFFFF;
        int opt = pe + 24;
        boolean plus = (in.getShort(opt) & 0xFFFF) == 0x20B;
        if (!plus && (in.getShort(opt) & 0xFFFF) != 0x10B) {
            throw new IOException("unknown PE format");
        }
        int dataDirectories = opt + (plus ? 112 : 96);
        int sectionAlignment = in.getInt(opt + 32);
        int fileAlignment = in.getInt(opt + 36);
        int sizeOfHeaders = in.getInt(opt + 60);
        int table = opt + optionalSize;
        if (sectionAlignment <= 0 || fileAlignment <= 0 || table + 40 * (sectionCount + 1) > file.length) {
            throw new IOException("unusual PE header");
        }

        int resourceRva = in.getInt(dataDirectories + 16);
        int imageEnd = 0;
        int fileEnd = 0;
        int firstRaw = Integer.MAX_VALUE;
        int oldSection = -1;
        for (int i = 0; i < sectionCount; i++) {
            int at = table + 40 * i;
            int virtualSize = in.getInt(at + 8);
            int va = in.getInt(at + 12);
            int rawSize = in.getInt(at + 16);
            int rawPtr = in.getInt(at + 20);
            imageEnd = Math.max(imageEnd, va + Math.max(virtualSize, rawSize));
            if (rawPtr > 0) {
                fileEnd = Math.max(fileEnd, rawPtr + rawSize);
                firstRaw = Math.min(firstRaw, rawPtr);
            }
            if (resourceRva != 0 && va <= resourceRva && resourceRva < va + Math.max(virtualSize, rawSize)) {
                oldSection = i;
            }
        }

        Node root = new Node();
        if (oldSection >= 0) {
            int at = table + 40 * oldSection;
            int va = in.getInt(at + 12);
            int rawSize = in.getInt(at + 16);
            int rawPtr = in.getInt(at + 20);
            if (rawPtr < 0 || rawSize < 0 || (long) rawPtr + rawSize > file.length) {
                throw new IOException("the resource section lies outside the file");
            }
            ByteBuffer section = ByteBuffer.wrap(file, rawPtr, rawSize).slice().order(ByteOrder.LITTLE_ENDIAN);
            root = parse(section, resourceRva - va, va, 0);
        }
        edit.accept(root);

        // An Authenticode signature (the certificate table, always at the end of the file) would no longer match.
        int length = file.length;
        int certificateOffset = in.getInt(dataDirectories + 32);
        int certificateSize = in.getInt(dataDirectories + 36);
        if (certificateOffset != 0) {
            if ((long) certificateOffset + certificateSize != file.length) {
                throw new IOException("the signature is not at the end of the file");
            }
            length = certificateOffset;
        }
        int newVa = alignUp(imageEnd, sectionAlignment);
        int newPtr = alignUp(Math.max(length, fileEnd), fileAlignment);
        byte[] resources = serialize(root, newVa);

        int header = table + 40 * sectionCount;
        if (header + 40 > Math.min(firstRaw, sizeOfHeaders)) {
            throw new IOException("the executable has no room for another section header");
        }
        byte[] out = new byte[newPtr + alignUp(resources.length, fileAlignment)];
        System.arraycopy(file, 0, out, 0, Math.min(length, file.length));
        System.arraycopy(resources, 0, out, newPtr, resources.length);
        ByteBuffer w = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);

        if (oldSection >= 0) {
            // The old resources stay in the file but are no longer listed as resources.
            byte[] name = ".orsrc\0\0".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, out, table + 40 * oldSection, 8);
        }
        System.arraycopy(".rsrc\0\0\0".getBytes(StandardCharsets.US_ASCII), 0, out, header, 8);
        w.putInt(header + 8, resources.length);
        w.putInt(header + 12, newVa);
        w.putInt(header + 16, alignUp(resources.length, fileAlignment));
        w.putInt(header + 20, newPtr);
        w.putInt(header + 36, 0x40000040); // initialized data, readable
        w.putShort(pe + 6, (short) (sectionCount + 1));
        w.putInt(opt + 56, alignUp(newVa + resources.length, sectionAlignment)); // SizeOfImage
        w.putInt(opt + 64, 0); // CheckSum: not checked for programs
        w.putInt(dataDirectories + 16, newVa);
        w.putInt(dataDirectories + 20, resources.length);
        if (certificateOffset != 0) {
            w.putInt(dataDirectories + 32, 0);
            w.putInt(dataDirectories + 36, 0);
        }
        return out;
    }

    /** Reads the resource tree out of the bytes of its section; {@code sectionRva} turns data addresses into offsets. */
    static Node parse(ByteBuffer section, int offset, int sectionRva, int depth) throws IOException {
        if (depth > 3 || offset < 0 || offset + 16 > section.limit()) {
            throw new IOException("damaged resource table");
        }
        Node node = new Node();
        int count = (section.getShort(offset + 12) & 0xFFFF) + (section.getShort(offset + 14) & 0xFFFF);
        if (offset + 16 + 8L * count > section.limit()) {
            throw new IOException("damaged resource table");
        }
        for (int i = 0; i < count; i++) {
            int entry = offset + 16 + 8 * i;
            int nameField = section.getInt(entry);
            int dataField = section.getInt(entry + 4);
            Node child;
            if (dataField < 0) {
                child = parse(section, dataField & 0x7FFFFFFF, sectionRva, depth + 1);
            } else {
                if (dataField + 16 > section.limit()) {
                    throw new IOException("damaged resource table");
                }
                int dataOffset = section.getInt(dataField) - sectionRva;
                int size = section.getInt(dataField + 4);
                if (dataOffset < 0 || size < 0 || (long) dataOffset + size > section.limit()) {
                    throw new IOException("a resource lies outside its section");
                }
                child = new Node();
                child.data = new byte[size];
                section.get(dataOffset, child.data);
                child.codePage = section.getInt(dataField + 8);
            }
            if (nameField < 0) {
                int at = nameField & 0x7FFFFFFF;
                int chars = section.getShort(at) & 0xFFFF;
                byte[] name = new byte[chars * 2];
                section.get(at + 2, name);
                node.named.put(new String(name, StandardCharsets.UTF_16LE), child);
            } else {
                node.ids.put(nameField, child);
            }
        }
        return node;
    }

    /** Writes the tree the way the loader expects it: all directories, then the data entries, the names, the data. */
    static byte[] serialize(Node root, int sectionRva) {
        List<Node> directories = new ArrayList<>();
        List<Node> leaves = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node directory = queue.poll();
            directories.add(directory);
            for (Node child : children(directory)) {
                (child.isLeaf() ? leaves : queue).add(child);
            }
        }
        IdentityHashMap<Node, Integer> offsets = new IdentityHashMap<>();
        int cursor = 0;
        for (Node directory : directories) {
            offsets.put(directory, cursor);
            cursor += 16 + 8 * (directory.named.size() + directory.ids.size());
        }
        for (Node leaf : leaves) {
            offsets.put(leaf, cursor);
            cursor += 16;
        }
        Map<String, Integer> names = new TreeMap<>();
        for (Node directory : directories) {
            for (String name : directory.named.keySet()) {
                if (!names.containsKey(name)) {
                    names.put(name, cursor);
                    cursor = alignUp(cursor + 2 + 2 * name.length(), 2);
                }
            }
        }
        cursor = alignUp(cursor, 4);
        IdentityHashMap<Node, Integer> dataAt = new IdentityHashMap<>();
        for (Node leaf : leaves) {
            dataAt.put(leaf, cursor);
            cursor = alignUp(cursor + leaf.data.length, 4);
        }

        ByteBuffer out = ByteBuffer.allocate(cursor).order(ByteOrder.LITTLE_ENDIAN);
        for (Node directory : directories) {
            int at = offsets.get(directory);
            out.putShort(at + 12, (short) directory.named.size());
            out.putShort(at + 14, (short) directory.ids.size());
            int entry = at + 16;
            for (var child : directory.named.entrySet()) {
                out.putInt(entry, 0x80000000 | names.get(child.getKey()));
                out.putInt(entry + 4, target(child.getValue(), offsets));
                entry += 8;
            }
            for (var child : directory.ids.entrySet()) {
                out.putInt(entry, child.getKey());
                out.putInt(entry + 4, target(child.getValue(), offsets));
                entry += 8;
            }
        }
        for (Node leaf : leaves) {
            int at = offsets.get(leaf);
            out.putInt(at, sectionRva + dataAt.get(leaf));
            out.putInt(at + 4, leaf.data.length);
            out.putInt(at + 8, leaf.codePage);
            out.put(dataAt.get(leaf), leaf.data);
        }
        for (var name : names.entrySet()) {
            out.putShort(name.getValue(), (short) name.getKey().length());
            out.put(name.getValue() + 2, name.getKey().getBytes(StandardCharsets.UTF_16LE));
        }
        return out.array();
    }

    private static int target(Node child, IdentityHashMap<Node, Integer> offsets) {
        return child.isLeaf() ? offsets.get(child) : 0x80000000 | offsets.get(child);
    }

    private static List<Node> children(Node directory) {
        List<Node> children = new ArrayList<>(directory.named.values());
        children.addAll(directory.ids.values());
        return children;
    }

    static int alignUp(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    // ── Icons and version information ───────────────────────────────────────────────────────────────────────────────

    /** The language the resources are written for (US English), which is what the shell shows by default. */
    static final int LANGUAGE = 0x0409;

    /** Replaces the icon of the program with the images of an {@code .ico} file. */
    static void setIcon(Node root, byte[] ico) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        if (ico.length < 6 || in.getShort(0) != 0 || in.getShort(2) != 1) {
            throw new IOException("not an .ico file");
        }
        int count = in.getShort(4) & 0xFFFF;
        if (count == 0 || ico.length < 6 + 16 * count) {
            throw new IOException("the .ico file has no images");
        }
        root.ids.remove(RT_ICON);
        root.ids.remove(RT_GROUP_ICON);
        ByteBuffer group = ByteBuffer.allocate(6 + 14 * count).order(ByteOrder.LITTLE_ENDIAN);
        group.putShort(2, (short) 1);
        group.putShort(4, (short) count);
        for (int i = 0; i < count; i++) {
            int entry = 6 + 16 * i;
            int size = in.getInt(entry + 8);
            int offset = in.getInt(entry + 12);
            if (size <= 0 || offset < 0 || (long) offset + size > ico.length) {
                throw new IOException("the .ico file is damaged");
            }
            leaf(root.child(RT_ICON), i + 1, Arrays.copyOfRange(ico, offset, offset + size));
            int at = 6 + 14 * i;
            group.put(at, ico, entry, 12); // width, height, colours, reserved, planes, bits, size
            group.putShort(at + 12, (short) (i + 1));
        }
        leaf(root.child(RT_GROUP_ICON), 1, group.array());
    }

    /** Replaces the version information. */
    static void setVersion(Node root, String fileVersion, Map<String, String> strings) {
        root.ids.remove(RT_VERSION);
        leaf(root.child(RT_VERSION), 1, versionInfo(fileVersion, strings));
    }

    private static void leaf(Node type, int name, byte[] data) {
        Node leaf = type.child(name).child(LANGUAGE);
        leaf.data = data;
    }

    /** {@code VS_VERSIONINFO}: a fixed block of numbers and a table of strings, every part padded to 4 bytes. */
    static byte[] versionInfo(String fileVersion, Map<String, String> strings) {
        long[] v = parseVersion(fileVersion);
        ByteBuffer fixed = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        fixed.putInt(0xFEEF04BD).putInt(0x00010000);
        fixed.putInt((int) (v[0] << 16 | v[1])).putInt((int) (v[2] << 16 | v[3])); // file version
        fixed.putInt((int) (v[0] << 16 | v[1])).putInt((int) (v[2] << 16 | v[3])); // product version
        fixed.putInt(0x3F).putInt(0).putInt(0x00040004).putInt(1).putInt(0).putInt(0).putInt(0);

        List<byte[]> table = new ArrayList<>();
        for (var string : strings.entrySet()) {
            byte[] value = utf16(string.getValue() + "\0");
            table.add(block(string.getKey(), value.length / 2, 1, value, List.of()));
        }
        byte[] stringTable = block("040904B0", 0, 1, new byte[0], table);
        byte[] stringFileInfo = block("StringFileInfo", 0, 1, new byte[0], List.of(stringTable));
        byte[] translation = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x04B00409).array();
        byte[] varFileInfo = block("VarFileInfo", 0, 1, new byte[0], List.of(block("Translation", 4, 0, translation, List.of())));
        return block("VS_VERSION_INFO", 52, 0, fixed.array(), List.of(stringFileInfo, varFileInfo));
    }

    /** One {@code length, valueLength, type, key, value, children} structure; its length leaves out the padding after it. */
    private static byte[] block(String key, int valueLength, int type, byte[] value, List<byte[]> children) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[6]);
        out.writeBytes(utf16(key + "\0"));
        pad(out);
        out.writeBytes(value);
        for (byte[] child : children) {
            pad(out);
            out.writeBytes(child);
        }
        byte[] bytes = out.toByteArray();
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort(0, (short) bytes.length);
        header.putShort(2, (short) valueLength);
        header.putShort(4, (short) type);
        return bytes;
    }

    private static void pad(ByteArrayOutputStream out) {
        while (out.size() % 4 != 0) {
            out.write(0);
        }
    }

    private static byte[] utf16(String text) {
        return text.getBytes(StandardCharsets.UTF_16LE);
    }

    /** {@code 1.2.3-beta} becomes 1.2.3.0: the numbers a version string starts with, the rest zero. */
    static long[] parseVersion(String version) {
        long[] numbers = new long[4];
        String[] parts = version == null ? new String[0] : version.split("[.\\-+]");
        for (int i = 0; i < 4 && i < parts.length; i++) {
            java.util.regex.Matcher digits = java.util.regex.Pattern.compile("^\\d{1,9}").matcher(parts[i]);
            if (!digits.find()) {
                break;
            }
            numbers[i] = Math.min(Long.parseLong(digits.group()), 65535);
        }
        return numbers;
    }
}
