package cn.linmumua.mutool.module;

import cn.linmumua.mutool.Mutool;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * Discovers, loads and unloads every {@link MutoolModule} without any central
 * registry list.
 *
 * <p><strong>Discovery</strong> scans the plugin's own class output — either the
 * packaged jar ({@code jar} file reported by the class' code source) or, when
 * running from an IDE / exploded build, the classes directory — for every
 * {@code *.class} under {@code cn/linmumua/mutool/module/}. Inner classes
 * (containing {@code $}) are skipped; each remaining class is instantiated via its
 * public no-arg constructor if — and only if — it implements {@link MutoolModule}.</p>
 *
 * <p>Modules are loaded in ascending {@link MutoolModule#id()} order and unloaded in
 * reverse. A failure in one module never aborts the others.</p>
 *
 * <p>This class never references any concrete module, so adding a new module needs
 * no edit here — the whole point of the framework.</p>
 */
public final class ModuleManager {

    private static final String MODULE_PACKAGE = "cn.linmumua.mutool.module";
    private static final String MODULE_PATH = MODULE_PACKAGE.replace('.', '/');

    private final Mutool plugin;
    private final List<MutoolModule> modules = new ArrayList<>();
    private final Map<String, MutoolModule> byId = new LinkedHashMap<>();

    public ModuleManager(Mutool plugin) {
        this.plugin = plugin;
    }

    /** Scan, sort by id(), and load each module. Idempotent-safe: clears previous state first. */
    public void loadAll() {
        clear();
        List<MutoolModule> discovered = discover();
        discovered.sort(Comparator.comparing(MutoolModule::id));

        for (MutoolModule module : discovered) {
            String id = safeId(module);
            try {
                module.load(this.plugin);
                this.modules.add(module);
                this.byId.put(id, module);
                this.plugin.getLogger().info("[Mutool] 已加载模块: " + id + " (enabled=" + module.isEnabled() + ")");
            } catch (Throwable throwable) {
                this.plugin.getLogger().log(Level.SEVERE, "[Mutool] 模块 [" + id + "] 加载失败，已跳过", throwable);
            }
        }

        List<String> ids = new ArrayList<>();
        for (MutoolModule module : this.modules) {
            ids.add(safeId(module));
        }
        this.plugin.getLogger().info("[Mutool] 共加载 " + this.modules.size() + " 个模块: " + ids);
    }

    /** Unload every loaded module in reverse load order; each in its own try/catch. */
    public void unloadAll() {
        for (int i = this.modules.size() - 1; i >= 0; i--) {
            MutoolModule module = this.modules.get(i);
            String id = safeId(module);
            try {
                module.unload();
                this.plugin.getLogger().info("[Mutool] 已卸载模块: " + id);
            } catch (Throwable throwable) {
                this.plugin.getLogger().log(Level.SEVERE, "[Mutool] 模块 [" + id + "] 卸载失败", throwable);
            }
        }
        clear();
    }

    /** Reload every loaded module (each guarded; a failure won't stop others). */
    public void reloadAll() {
        for (MutoolModule module : this.modules) {
            String id = safeId(module);
            try {
                module.reload();
            } catch (Throwable throwable) {
                this.plugin.getLogger().log(Level.SEVERE, "[Mutool] 模块 [" + id + "] 重载失败", throwable);
            }
        }
    }

    public List<MutoolModule> modules() {
        return Collections.unmodifiableList(this.modules);
    }

    public MutoolModule get(String id) {
        return this.byId.get(id);
    }

    // --- internals ---

    private void clear() {
        this.modules.clear();
        this.byId.clear();
    }

    private static String safeId(MutoolModule module) {
        try {
            String id = module.id();
            return id == null ? module.getClass().getName() : id;
        } catch (Throwable throwable) {
            return module.getClass().getName();
        }
    }

    /** Return an instantiated list of every module found under the module package. */
    private List<MutoolModule> discover() {
        List<MutoolModule> result = new ArrayList<>();
        Set<String> classNames = new LinkedHashSet<>();
        try {
            URL location = this.plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            File root = new File(location.toURI());

            if (root.isFile()) {
                collectFromJar(root, classNames);
            } else if (root.isDirectory()) {
                collectFromDirectory(new File(root, MODULE_PATH), classNames);
            } else {
                this.plugin.getLogger().warning("[Mutool] 无法识别的 code source: " + root);
            }
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(Level.SEVERE, "[Mutool] 扫描模块失败", throwable);
        }

        ClassLoader loader = this.plugin.getClass().getClassLoader();
        for (String className : classNames) {
            MutoolModule module = instantiate(className, loader);
            if (module != null) {
                result.add(module);
            }
        }
        return result;
    }

    private void collectFromJar(File jarFile, Set<String> out) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(MODULE_PATH + "/") || !name.endsWith(".class")) {
                    continue;
                }
                addClassName(name, out);
            }
        }
    }

    private void collectFromDirectory(File directory, Set<String> out) {
        if (directory == null || !directory.isDirectory()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFromDirectory(child, out);
            } else if (child.getName().endsWith(".class")) {
                // Rebuild the FQCN from the path segment starting at the module package root.
                String path = child.getAbsolutePath().replace(File.separatorChar, '/');
                int idx = path.indexOf(MODULE_PATH + "/");
                if (idx >= 0) {
                    addClassName(path.substring(idx), out);
                }
            }
        }
    }

    private void addClassName(String resourcePath, Set<String> out) {
        String normalized = resourcePath.replace('/', '.');
        String className = normalized.substring(0, normalized.length() - ".class".length());
        if (className.indexOf('$') >= 0) {
            return; // skip inner / anonymous classes
        }
        out.add(className);
    }

    private MutoolModule instantiate(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            if (!MutoolModule.class.isAssignableFrom(type)) {
                return null;
            }
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                return null;
            }
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (MutoolModule) constructor.newInstance();
        } catch (NoSuchMethodException ignored) {
            this.plugin.getLogger().warning("[Mutool] 模块缺少无参构造，已跳过: " + className);
            return null;
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(Level.SEVERE, "[Mutool] 实例化模块失败: " + className, throwable);
            return null;
        }
    }
}
