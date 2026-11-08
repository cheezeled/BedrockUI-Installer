package dev.bedrockui.exporter;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Copies the files the Bedrock UI mod reads out of a Minecraft Bedrock APK into bedrock_data.zip, using the same
 * layout as tools/export_lite.py in the BedrockUI mod repository (paths relative to Bedrock's data root). No Android APIs here, so it can be
 * tested on a PC.
 *
 * The APK layout is not assumed: an entry is recognised by the "resource_packs/vanilla/" or "gui/dist/hbui/" part
 * of its path, wherever that sits (assets/, assets/data/, ...). Both packed .brarchive files and loose files work;
 * loose ui/ and textures/ are only taken when the APK has no UI archives (the mod reads either).
 */
public final class Exporter {
	public interface Progress {
		void update(int files, long bytes);
	}

	public static final class Result {
		public int files, archives, loose, hbui;
		public long bytes;
		public boolean lang, ui;
		public final StringBuilder log = new StringBuilder();
	}

	static final String VANILLA = "resource_packs/vanilla/";
	static final String HBUI = "gui/dist/hbui/";
	private static final String[] LOOSE = {"ui/", "textures/ui/", "textures/gui/", "textures/items/", "textures/blocks/",
		"textures/environment/", "textures/misc/", "textures/map/", "textures/colormap/"};

	static final int SKIP = 0, ARCHIVE = 1, LOOSE_FILE = 2, LANG = 3, ORE = 4;

	private Exporter() {
	}

	/** Path inside bedrock_data.zip for an entry of the APK, or null when it is not under a known root. */
	static String key(String name) {
		name = name.replace('\\', '/');
		int v = name.indexOf(VANILLA);
		int h = name.indexOf(HBUI);
		if (v >= 0 && (h < 0 || v <= h)) return name.substring(v);
		if (h >= 0) return name.substring(h);
		return null;
	}

	static int kind(String key) {
		if (key.endsWith("/")) return SKIP;
		if (key.startsWith(VANILLA)) {
			String r = key.substring(VANILLA.length());
			if (r.startsWith("__brarchive/")) {
				String a = r.substring("__brarchive/".length());
				if (!a.endsWith(".brarchive")) return SKIP;
				return a.equals("ui.brarchive") || a.startsWith("ui/") || a.startsWith("textures") ? ARCHIVE : SKIP;
			}
			if (r.equals("texts/en_US.lang") || r.equals("texts/languages.json")) return LANG;
			for (String l : LOOSE) if (r.startsWith(l)) return LOOSE_FILE;
			return SKIP;
		}
		String r = key.substring(HBUI.length());
		if (r.equals("atlas.json")) return ORE;
		if (r.startsWith("assets/") && r.endsWith(".png") && r.indexOf('/', 7) < 0) return ORE;
		if (r.startsWith("fonts/")) {
			String f = r.substring(6);
			if (f.indexOf('/') < 0 && (f.startsWith("Minecraft-Ten") || f.startsWith("Minecraft-Five-Bold"))) return ORE;
		}
		return SKIP;
	}

	private static boolean isUi(String key, int kind) {
		if (kind == ARCHIVE) return key.equals(VANILLA + "__brarchive/ui.brarchive") || key.startsWith(VANILLA + "__brarchive/ui/");
		return kind == LOOSE_FILE && key.startsWith(VANILLA + "ui/") && key.endsWith(".json");
	}

	/** Installed APKs (base + splits): random access, so nothing unneeded is read. */
	public static Result exportApks(List<File> apks, OutputStream os, Progress p, Map<String, byte[]> extras)
		throws IOException {
		Result res = new Result();
		List<ZipFile> zips = new ArrayList<>();
		try {
			Map<String, Object[]> picked = new LinkedHashMap<>();
			boolean hasArchives = false;
			for (File f : apks) {
				ZipFile z = new ZipFile(f);
				zips.add(z);
				res.log.append("APK ").append(f.getName()).append(" (").append(mb(f.length())).append(" MB)\n");
				Map<String, Integer> tree = new HashMap<>();
				for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
					ZipEntry e = en.nextElement();
					count(tree, e.getName());
					if (e.isDirectory()) continue;
					String k = key(e.getName());
					if (k == null) continue;
					int kind = kind(k);
					if (kind == SKIP || picked.containsKey(k)) continue;
					if (kind == ARCHIVE) hasArchives = true;
					picked.put(k, new Object[] {z, e, kind});
				}
				appendTree(res.log, tree);
			}
			try (ZipOutputStream out = new ZipOutputStream(os)) {
				writeExtras(out, extras);
				for (Map.Entry<String, Object[]> m : picked.entrySet()) {
					int kind = (Integer) m.getValue()[2];
					if (kind == LOOSE_FILE && hasArchives) continue;
					ZipFile z = (ZipFile) m.getValue()[0];
					try (InputStream in = z.getInputStream((ZipEntry) m.getValue()[1])) {
						write(out, m.getKey(), kind, in, res, p);
					}
				}
				out.finish();
			}
		} finally {
			for (ZipFile z : zips) {
				try {
					z.close();
				} catch (IOException ignored) {
				}
			}
		}
		summary(res);
		return res;
	}

	/** A picked file: an APK, or an .apks/.xapk bundle that contains APKs. Read once from start to end. */
	public static Result exportStream(InputStream src, OutputStream os, Progress p, Map<String, byte[]> extras)
		throws IOException {
		Result res = new Result();
		Map<String, Integer> tree = new HashMap<>();
		Set<String> done = new HashSet<>();
		try (ZipInputStream zin = new ZipInputStream(src); ZipOutputStream out = new ZipOutputStream(os)) {
			writeExtras(out, extras);
			scan(zin, "", out, res, p, tree, done);
			out.finish();
		}
		appendTree(res.log, tree);
		summary(res);
		return res;
	}

	private static void scan(ZipInputStream zin, String outer, ZipOutputStream out, Result res, Progress p,
		Map<String, Integer> tree, Set<String> done) throws IOException {
		ZipEntry e;
		while ((e = zin.getNextEntry()) != null) {
			String name = e.getName();
			count(tree, outer + name);
			if (e.isDirectory()) continue;
			if (name.toLowerCase().endsWith(".apk")) {
				res.log.append("APK ").append(name).append('\n');
				scan(new ZipInputStream(new FilterInputStream(zin) {
					@Override
					public void close() {
					}
				}), name + "!/", out, res, p, tree, done);
				continue;
			}
			String k = key(name);
			if (k == null) continue;
			int kind = kind(k);
			if (kind == SKIP || !done.add(k)) continue;
			write(out, k, kind, zin, res, p);
		}
	}

	/** Files of our own (the copyright notice) put at the top of bedrock_data.zip; not counted as Bedrock files. */
	private static void writeExtras(ZipOutputStream out, Map<String, byte[]> extras) throws IOException {
		if (extras == null) return;
		for (Map.Entry<String, byte[]> e : extras.entrySet()) {
			out.putNextEntry(new ZipEntry(e.getKey()));
			out.write(e.getValue());
			out.closeEntry();
		}
	}

	private static void write(ZipOutputStream out, String key, int kind, InputStream in, Result res, Progress p)
		throws IOException {
		out.putNextEntry(new ZipEntry(key));
		byte[] buf = new byte[1 << 16];
		long last = res.bytes;
		int n;
		while ((n = in.read(buf)) > 0) {
			out.write(buf, 0, n);
			res.bytes += n;
			if (p != null && res.bytes - last > (1 << 20)) {
				last = res.bytes;
				p.update(res.files, res.bytes);
			}
		}
		out.closeEntry();
		res.files++;
		if (kind == ARCHIVE) res.archives++;
		else if (kind == LOOSE_FILE) res.loose++;
		else if (kind == ORE) res.hbui++;
		else if (kind == LANG && key.endsWith(".lang")) res.lang = true;
		if (isUi(key, kind)) res.ui = true;
		if (p != null) p.update(res.files, res.bytes);
	}

	private static void summary(Result r) {
		r.log.append("Result: ").append(r.files).append(" files, ").append(mb(r.bytes)).append(" MB; archives=")
			.append(r.archives).append(" loose=").append(r.loose).append(" oreui=").append(r.hbui)
			.append(" lang=").append(r.lang).append(" ui=").append(r.ui).append('\n');
	}

	/** Entry count per first three path segments, so an unknown APK layout can be read from the log. */
	private static void count(Map<String, Integer> tree, String name) {
		String[] s = name.replace('\\', '/').split("/");
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < Math.min(3, s.length - 1); i++) b.append(s[i]).append('/');
		if (b.length() == 0) b.append("(root)");
		tree.merge(b.toString(), 1, Integer::sum);
	}

	private static void appendTree(StringBuilder log, Map<String, Integer> tree) {
		List<Map.Entry<String, Integer>> l = new ArrayList<>(tree.entrySet());
		l.sort((a, b) -> b.getValue() - a.getValue());
		for (int i = 0; i < Math.min(30, l.size()); i++) {
			log.append("  ").append(l.get(i).getValue()).append("  ").append(l.get(i).getKey()).append('\n');
		}
	}

	static String mb(long bytes) {
		return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1e6);
	}
}
