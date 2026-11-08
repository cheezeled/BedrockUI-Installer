package dev.bedrockui.exporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bedrock UI Installer: puts a Bedrock UI mod jar the user imported (downloaded from the mod's own page, so its
 * credits and license travel with it; nothing is bundled) into a launcher's game folder, and exports bedrock_data.zip
 * from the user's own Minecraft Bedrock next to it.
 */
public class MainActivity extends Activity {
	private static final String[] PACKAGES = {"com.mojang.minecraftpe", "com.mojang.minecraftpe.preview"};
	private static final int REQ_TREE = 1, REQ_SAVE = 2, REQ_MOD = 4;

	/** Language choices: "" = system. Names are written in their own language. */
	private static final String[] LANG_CODES = {"", "in", "en", "zh", "vi", "fil", "ja"};
	private static final String[] LANG_NAMES = {null, "Bahasa Indonesia", "English", "中文（简体）", "Tiếng Việt",
		"Filipino", "日本語"};

	private SharedPreferences prefs;
	private TextView source, result, logView;
	private Button installBtn, saveBtn;
	private TextView modInfo, reqBedrock, reqMod;
	private Button importBtn;
	private ProgressBar bar;

	private final List<File> installed = new ArrayList<>();
	private String installedVersion;
	/** Minecraft Bedrock must come from an official store: the exported files are the user's own licensed copy. */
	private boolean official;
	private String installer;
	private static final java.util.Set<String> STORES = new java.util.HashSet<>(Arrays.asList(
		"com.android.vending",           // Google Play
		"com.amazon.venezia",            // Amazon Appstore
		"com.sec.android.app.samsungapps", // Samsung Galaxy Store
		"com.huawei.appmarket",          // Huawei AppGallery
		"com.xiaomi.market", "com.xiaomi.mipicks", "com.miui.packageinstaller.market")); // Xiaomi GetApps
	/** The mod jar the user imported (kept in the app's private storage), and what its fabric.mod.json says. */
	private File modFile;
	private ModMeta modMeta;
	private final StringBuilder log = new StringBuilder();

	// ---- language ---------------------------------------------------------------------------------------------

	@Override
	protected void attachBaseContext(Context base) {
		String lang = base.getSharedPreferences("prefs", MODE_PRIVATE).getString("lang", "");
		super.attachBaseContext(lang.isEmpty() ? base : localized(base, lang));
	}

	private static Context localized(Context base, String lang) {
		Configuration c = new Configuration(base.getResources().getConfiguration());
		c.setLocale(locale(lang));
		return base.createConfigurationContext(c);
	}

	private static Locale locale(String lang) {
		switch (lang) {
			case "zh":
				return Locale.SIMPLIFIED_CHINESE;
			case "fil":
				return new Locale("fil", "PH");
			default:
				return new Locale(lang);
		}
	}

	// ---- UI ---------------------------------------------------------------------------------------------------

	@Override
	protected void onCreate(Bundle saved) {
		super.onCreate(saved);
		prefs = getSharedPreferences("prefs", MODE_PRIVATE);

		ScrollView scroll = new ScrollView(this);
		scroll.setFitsSystemWindows(true);
		scroll.setBackgroundColor(0xFF313233);
		LinearLayout col = new LinearLayout(this);
		col.setOrientation(LinearLayout.VERTICAL);
		int pad = dp(16);
		col.setPadding(pad, pad, pad, pad);
		scroll.addView(col);

		TextView title = text(getString(R.string.app_name), 22, Color.WHITE);
		title.setTypeface(Typeface.DEFAULT_BOLD);
		col.addView(title);
		col.addView(languageRow());

		// Copyright warning, always visible
		LinearLayout warn = new LinearLayout(this);
		warn.setOrientation(LinearLayout.VERTICAL);
		warn.setPadding(pad, pad, pad, pad);
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(0xFF4A1E1E);
		bg.setStroke(dp(2), 0xFFD9534F);
		bg.setCornerRadius(dp(4));
		warn.setBackground(bg);
		TextView wt = text(getString(R.string.warning_title), 16, 0xFFFFB3B0);
		wt.setTypeface(Typeface.DEFAULT_BOLD);
		warn.addView(wt);
		warn.addView(gap(text(getString(R.string.warning), 13, Color.WHITE), 6));
		col.addView(gap(warn, 12));

		col.addView(gap(text(getString(R.string.intro), 14, 0xFFD0D1D4), 12));

		col.addView(gap(header(R.string.req_title), 20));
		// 1. the official game
		reqBedrock = text("", 15, Color.WHITE);
		col.addView(gap(reqBedrock, 8));
		source = text("", 13, 0xFFD0D1D4);
		source.setPadding(dp(24), 0, 0, 0);
		col.addView(gap(source, 2));
		// 2. the mod file, downloaded from the mod's own page
		reqMod = text("", 15, Color.WHITE);
		col.addView(gap(reqMod, 14));
		modInfo = text("", 13, 0xFFD0D1D4);
		modInfo.setPadding(dp(24), 0, 0, 0);
		modInfo.setTextIsSelectable(true);
		col.addView(gap(modInfo, 2));
		importBtn = button(getString(R.string.import_mod), 0xFF58585A);
		importBtn.setOnClickListener(v -> {
			Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
			i.addCategory(Intent.CATEGORY_OPENABLE);
			i.setType("*/*");
			startActivityForResult(i, REQ_MOD);
		});
		col.addView(gap(importBtn, 6));

		installBtn = button(getString(R.string.install), 0xFF3C8527);
		installBtn.setOnClickListener(v -> {
			if (!ready()) {
				Toast.makeText(this, R.string.need_both, Toast.LENGTH_LONG).show();
				return;
			}
			startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
		});
		col.addView(gap(installBtn, 20));
		col.addView(gap(text(getString(R.string.install_hint), 12, 0xFF8C8D90), 4));
		saveBtn = button(getString(R.string.save_zip), 0xFF58585A);
		saveBtn.setOnClickListener(v -> {
			if (!ready()) {
				Toast.makeText(this, R.string.need_both, Toast.LENGTH_LONG).show();
				return;
			}
			Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
			i.addCategory(Intent.CATEGORY_OPENABLE);
			i.setType("application/zip");
			i.putExtra(Intent.EXTRA_TITLE, "bedrock_data.zip");
			startActivityForResult(i, REQ_SAVE);
		});
		col.addView(gap(saveBtn, 12));

		bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		bar.setIndeterminate(true);
		bar.setVisibility(View.GONE);
		col.addView(gap(bar, 12));
		result = text("", 15, Color.WHITE);
		col.addView(gap(result, 12));

		Button copyBtn = button(getString(R.string.copy_log), 0xFF48494A);
		copyBtn.setOnClickListener(v -> {
			ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
			cm.setPrimaryClip(ClipData.newPlainText("log", log.toString()));
			Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
		});
		col.addView(gap(copyBtn, 20));
		logView = text("", 11, 0xFF8C8D90);
		logView.setTypeface(Typeface.MONOSPACE);
		logView.setTextIsSelectable(true);
		col.addView(gap(logView, 8));

		setContentView(scroll);
		detect();
		addLog("App " + appVersion());
		updateSource();
		loadImportedMod();
		updateMod();

		if (!prefs.getBoolean("agreed", false)) {
			new AlertDialog.Builder(this)
				.setTitle(R.string.warning_title)
				.setMessage(R.string.warning)
				.setCancelable(false)
				.setPositiveButton(R.string.agree, (d, w) -> prefs.edit().putBoolean("agreed", true).apply())
				.show();
		}
	}

	private View languageRow() {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(android.view.Gravity.CENTER_VERTICAL);
		row.addView(text(getString(R.string.language) + "  ", 14, 0xFFD0D1D4));
		String[] names = LANG_NAMES.clone();
		names[0] = getString(R.string.lang_auto);
		Spinner sp = new Spinner(this);
		ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
		ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		sp.setAdapter(ad);
		String cur = prefs.getString("lang", "");
		sp.setSelection(Math.max(0, Arrays.asList(LANG_CODES).indexOf(cur)), false);
		sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
				if (LANG_CODES[pos].equals(prefs.getString("lang", ""))) return;
				prefs.edit().putString("lang", LANG_CODES[pos]).apply();
				recreate();
			}

			@Override
			public void onNothingSelected(AdapterView<?> p) {
			}
		});
		row.addView(sp);
		return gap(row, 8);
	}

	/** Finds Minecraft Bedrock and its APK files (base + splits). Readable by any app; needs only <queries>. */
	private void detect() {
		PackageManager pm = getPackageManager();
		addLog("Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
			+ " (API " + Build.VERSION.SDK_INT + ")");
		for (String pkg : PACKAGES) {
			try {
				PackageInfo pi = pm.getPackageInfo(pkg, 0);
				ApplicationInfo ai = pi.applicationInfo;
				installed.add(new File(ai.sourceDir));
				if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) installed.add(new File(s));
				installedVersion = pi.versionName;
				installer = installerOf(pm, pkg);
				official = installer != null && STORES.contains(installer);
				addLog("Package: " + pkg + " " + pi.versionName + ", installed by " + installer + (official ? " (official store)" : " (NOT an official store)"));
				for (File f : installed) addLog("  " + f + (f.canRead() ? "" : "  [not readable]"));
				return;
			} catch (PackageManager.NameNotFoundException ignored) {
			}
		}
		addLog("Minecraft Bedrock not installed");
	}

	@SuppressWarnings("deprecation")
	private static String installerOf(PackageManager pm, String pkg) {
		try {
			if (Build.VERSION.SDK_INT >= 30) return pm.getInstallSourceInfo(pkg).getInstallingPackageName();
			return pm.getInstallerPackageName(pkg);
		} catch (Exception e) {
			return null;
		}
	}

	private boolean bedrockOk() {
		return !installed.isEmpty() && official;
	}

	/** Both requirements: the official Minecraft Bedrock and the imported Bedrock UI mod file. */
	private boolean ready() {
		return bedrockOk() && modFile != null;
	}

	private void updateSource() {
		if (installed.isEmpty()) source.setText(R.string.src_none);
		else if (!official) source.setText(getString(R.string.src_unofficial, installedVersion));
		else source.setText(getString(R.string.src_installed, installedVersion, installed.size()));
		reqBedrock.setText((bedrockOk() ? "\u2714  " : "\u2716  ") + getString(R.string.req_bedrock));
		reqBedrock.setTextColor(bedrockOk() ? 0xFF8EE07A : 0xFFFF8A80);
		updateGate();
	}

	private void updateGate() {
		if (reqMod == null || installBtn == null) return;
		boolean mod = modFile != null;
		reqMod.setText((mod ? "\u2714  " : "\u2716  ") + getString(R.string.req_mod));
		reqMod.setTextColor(mod ? 0xFF8EE07A : 0xFFFF8A80);
		boolean ok = ready();
		installBtn.setAlpha(ok ? 1f : 0.4f);
		saveBtn.setAlpha(ok ? 1f : 0.4f);
	}

	// ---- imported mod -----------------------------------------------------------------------------------------

	/** What a mod jar's fabric.mod.json says; shown so the mod's own credits and license stay visible. */
	static final class ModMeta {
		String id, name, version, authors, license, minecraft, homepage;
	}

	static ModMeta readMeta(File jar) throws Exception {
		try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar)) {
			java.util.zip.ZipEntry e = z.getEntry("fabric.mod.json");
			if (e == null) return null;
			String json;
			try (InputStream in = z.getInputStream(e)) {
				java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
				json = b.toString("UTF-8");
			}
			org.json.JSONObject o = new org.json.JSONObject(json);
			ModMeta m = new ModMeta();
			m.id = o.optString("id", "?");
			m.name = o.optString("name", m.id);
			m.version = o.optString("version", "?");
			m.authors = people(o.opt("authors"));
			m.license = people(o.opt("license"));
			org.json.JSONObject dep = o.optJSONObject("depends");
			m.minecraft = dep != null && dep.has("minecraft") ? people(dep.opt("minecraft")) : "?";
			org.json.JSONObject contact = o.optJSONObject("contact");
			m.homepage = contact != null ? contact.optString("homepage", contact.optString("sources", "")) : "";
			return m;
		}
	}

	/** A string, or an array of strings / {"name": ...} objects, as one comma-separated string. */
	private static String people(Object v) {
		if (v == null) return "?";
		if (v instanceof org.json.JSONArray) {
			org.json.JSONArray a = (org.json.JSONArray) v;
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < a.length(); i++) {
				Object x = a.opt(i);
				if (x instanceof org.json.JSONObject) x = ((org.json.JSONObject) x).optString("name", "?");
				if (b.length() > 0) b.append(", ");
				b.append(x);
			}
			return b.length() == 0 ? "?" : b.toString();
		}
		return v.toString();
	}

	private File modDir() {
		return new File(getFilesDir(), "mod");
	}

	private void loadImportedMod() {
		modFile = null;
		modMeta = null;
		File[] files = modDir().listFiles();
		if (files == null) return;
		for (File f : files) {
			if (!f.getName().endsWith(".jar")) continue;
			try {
				ModMeta m = readMeta(f);
				if (m != null && "bedrockui".equals(m.id)) {
					modFile = f;
					modMeta = m;
					return;
				}
			} catch (Exception ignored) {
			}
		}
	}

	private void updateMod() {
		if (modMeta == null) {
			modInfo.setText(R.string.mod_none);
			updateGate();
			return;
		}
		String info = getString(R.string.mod_info, modMeta.name, modMeta.version, modMeta.authors, modMeta.license,
			modMeta.minecraft);
		if (!modMeta.homepage.isEmpty()) info += "\n" + modMeta.homepage;
		modInfo.setText(info + "\n" + modFile.getName());
		updateGate();
	}

	/** Copies a picked jar into private storage after checking it is the Bedrock UI mod. */
	private void importMod(Uri uri) throws Exception {
		ContentResolver cr = getContentResolver();
		String name = displayName(cr, uri).replace('/', '_');
		File tmp = new File(getCacheDir(), "import.jar");
		try (InputStream in = cr.openInputStream(uri); OutputStream os = new java.io.FileOutputStream(tmp)) {
			if (in == null) throw new IOException("cannot read " + name);
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
		}
		ModMeta m;
		try {
			m = readMeta(tmp);
		} catch (Exception e) {
			m = null; // not a zip/jar
		}
		if (m == null || !"bedrockui".equals(m.id)) {
			tmp.delete();
			String what = m == null ? name : name + ", id=" + m.id;
			addLog("Rejected mod file: " + what);
			runOnUiThread(() -> {
				setBusy(false);
				result.setText(getString(R.string.mod_wrong, what));
			});
			return;
		}
		if (!name.endsWith(".jar")) name = "bedrockui-" + m.version + ".jar";
		File dir = modDir();
		File[] old = dir.listFiles();
		if (old != null) for (File f : old) f.delete();
		dir.mkdirs();
		File dest = new File(dir, name);
		if (!tmp.renameTo(dest)) throw new IOException("cannot store " + name);
		addLog("Imported mod: " + name + " (" + m.name + " " + m.version + ", license " + m.license + ")");
		runOnUiThread(() -> {
			setBusy(false);
			loadImportedMod();
			updateMod();
		});
	}

	@Override
	protected void onActivityResult(int req, int code, Intent data) {
		super.onActivityResult(req, code, data);
		if (code != RESULT_OK || data == null || data.getData() == null) return;
		Uri uri = data.getData();
		if (req == REQ_MOD) {
			work(() -> importMod(uri));
		} else if (req == REQ_TREE) {
			if (!ready()) return;
			work(() -> installTo(uri, modFile, true));
		} else if (req == REQ_SAVE) {
			if (!ready()) return;
			work(() -> {
				Exporter.Result r = exportTo(uri);
				done(null, r, false);
			});
		}
	}

	// ---- work -------------------------------------------------------------------------------------------------

	private interface Job {
		void run() throws Exception;
	}

	private void work(Job job) {
		setBusy(true);
		result.setText("");
		new Thread(() -> {
			try {
				job.run();
			} catch (Throwable t) {
				addLog("ERROR " + t);
				for (StackTraceElement s : t.getStackTrace()) addLog("    at " + s);
				runOnUiThread(() -> {
					setBusy(false);
					result.setText(getString(R.string.failed, t.getMessage() != null ? t.getMessage() : t.toString()));
				});
			}
		}, "install").start();
	}

	/** Writes mods/<jar> and/or bedrock_data.zip into the picked game folder. */
	private void installTo(Uri tree, File jar, boolean withData) throws Exception {
		ContentResolver cr = getContentResolver();
		String rootId = DocumentsContract.getTreeDocumentId(tree);
		Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
		String rootName = displayName(cr, root);
		addLog("Game folder: " + rootName + "  (" + tree + ")");
		if ("mods".equalsIgnoreCase(rootName)) {
			runOnUiThread(() -> {
				setBusy(false);
				result.setText(R.string.picked_mods);
			});
			return;
		}
		String installedJar = null;
		if (jar != null) {
			String modsId = child(cr, tree, rootId, "mods");
			Uri mods = modsId != null ? DocumentsContract.buildDocumentUriUsingTree(tree, modsId)
				: DocumentsContract.createDocument(cr, root, Document.MIME_TYPE_DIR, "mods");
			if (mods == null) throw new IOException("cannot create mods folder");
			modsId = DocumentsContract.getDocumentId(mods);
			// an older or other-version Bedrock UI jar next to the new one would stop Fabric from starting
			for (String[] c : children(cr, tree, modsId)) {
				if ((c[1].startsWith("bedrockui-") && c[1].endsWith(".jar")) || c[1].equals(jar.getName())) {
					DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, c[0]));
					addLog("Removed mods/" + c[1]);
				}
			}
			Uri out = DocumentsContract.createDocument(cr, mods, "application/octet-stream", jar.getName());
			if (out == null) throw new IOException("cannot create mods/" + jar.getName());
			try (InputStream in = new java.io.FileInputStream(jar); OutputStream os = open(cr, out)) {
				byte[] buf = new byte[1 << 16];
				int n;
				while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
			}
			installedJar = displayName(cr, out);
			addLog("Wrote mods/" + installedJar);
		}
		Exporter.Result r = null;
		if (withData) {
			String old = child(cr, tree, rootId, "bedrock_data.zip");
			if (old != null) DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, old));
			Uri zip = DocumentsContract.createDocument(cr, root, "application/zip", "bedrock_data.zip");
			if (zip == null) throw new IOException("cannot create bedrock_data.zip");
			r = exportTo(zip);
		}
		done(installedJar, r, true);
	}

	/** Exports bedrock_data.zip to uri; deletes it again when nothing was found. */
	private Exporter.Result exportTo(Uri target) throws Exception {
		ContentResolver cr = getContentResolver();
		Exporter.Progress prog = (files, bytes) -> runOnUiThread(() ->
			result.setText(getString(R.string.working, files, bytes / 1e6)));
		Map<String, byte[]> extras = new LinkedHashMap<>();
		extras.put("COPYRIGHT_README.txt", notice());
		Exporter.Result r;
		try (OutputStream os = new BufferedOutputStream(open(cr, target), 1 << 16)) {
			// only ever from the installed official game (no loose APK files)
			r = Exporter.exportApks(installed, os, prog, extras);
		}
		synchronized (this) {
			log.append(r.log);
		}
		if (r.files == 0) {
			try {
				DocumentsContract.deleteDocument(cr, target); // don't leave a zip without Bedrock files behind
			} catch (Throwable ignored) {
			}
		}
		return r;
	}

	private void done(String jar, Exporter.Result r, boolean inGameFolder) {
		runOnUiThread(() -> {
			setBusy(false);
			StringBuilder msg = new StringBuilder();
			if (r != null && r.files == 0) {
				msg.append(getString(R.string.nothing));
				if (jar != null) msg.append("\n\n").append(getString(R.string.done_mod, jar));
			} else {
				msg.append(getString(R.string.done));
				if (jar != null) msg.append("\n").append(getString(R.string.done_mod, jar));
				if (r != null) msg.append("\n").append(getString(R.string.done_data, r.files, r.bytes / 1e6));
				if (r != null && !r.ui) msg.append("\n\n").append(getString(R.string.no_ui));
				msg.append("\n\n").append(getString(inGameFolder ? R.string.next_steps : R.string.next_zip));
				if (r != null) msg.append("\n\n").append(getString(R.string.warning_title));
			}
			result.setText(msg);
			logView.setText(log);
		});
	}

	/** The copyright warning in every app language, written into bedrock_data.zip. */
	private byte[] notice() {
		StringBuilder b = new StringBuilder();
		for (int i = 1; i < LANG_CODES.length; i++) {
			Context c = localized(getBaseContext(), LANG_CODES[i]);
			b.append("== ").append(LANG_NAMES[i]).append(" ==\n");
			b.append(c.getString(R.string.warning_title)).append("\n\n").append(c.getString(R.string.warning)).append("\n\n");
		}
		return b.toString().replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
	}

	// ---- documents --------------------------------------------------------------------------------------------

	private static List<String[]> children(ContentResolver cr, Uri tree, String parentId) {
		List<String[]> out = new ArrayList<>();
		Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
		try (Cursor c = cr.query(uri, new String[] {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME},
			null, null, null)) {
			while (c != null && c.moveToNext()) out.add(new String[] {c.getString(0), c.getString(1)});
		}
		return out;
	}

	private static String child(ContentResolver cr, Uri tree, String parentId, String name) {
		for (String[] c : children(cr, tree, parentId)) if (name.equals(c[1])) return c[0];
		return null;
	}

	private static String displayName(ContentResolver cr, Uri uri) {
		try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
			if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
		} catch (Exception ignored) {
		}
		String s = uri.getLastPathSegment();
		return s == null ? "?" : s.substring(s.lastIndexOf('/') + 1);
	}

	private static OutputStream open(ContentResolver cr, Uri uri) throws IOException {
		try {
			OutputStream os = cr.openOutputStream(uri, "wt");
			if (os != null) return os;
		} catch (Exception ignored) {
			// some providers don't support truncate mode
		}
		OutputStream os = cr.openOutputStream(uri, "w");
		if (os == null) throw new IOException("cannot write " + uri);
		return os;
	}

	// ---- helpers ----------------------------------------------------------------------------------------------

	private String appVersion() {
		try {
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		} catch (PackageManager.NameNotFoundException e) {
			return "?";
		}
	}

	private void setBusy(boolean busy) {
		bar.setVisibility(busy ? View.VISIBLE : View.GONE);
		installBtn.setEnabled(!busy);
		saveBtn.setEnabled(!busy);
		importBtn.setEnabled(!busy);
		if (!busy) updateGate();
		if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	}

	private synchronized void addLog(String s) {
		log.append(s).append('\n');
		if (logView != null) runOnUiThread(() -> logView.setText(log));
	}

	private TextView header(int res) {
		TextView t = text(getString(res), 16, Color.WHITE);
		t.setTypeface(Typeface.DEFAULT_BOLD);
		return t;
	}

	private TextView text(String s, int sp, int color) {
		TextView t = new TextView(this);
		t.setText(s);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTextColor(color);
		return t;
	}

	private Button button(String s, int color) {
		Button b = new Button(this);
		b.setText(s);
		b.setAllCaps(false);
		b.setTextColor(Color.WHITE);
		b.getBackground().setTint(color);
		return b;
	}

	private <V extends View> V gap(V v, int topDp) {
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		lp.topMargin = dp(topDp);
		v.setLayoutParams(lp);
		return v;
	}

	private int dp(int v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}
}
