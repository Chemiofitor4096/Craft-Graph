package dev.craftgraph.extract.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

/**
 * 进程内唯一的 profile 注册表。
 *
 * <p>内置 profile 在 core 的 jar 里，用户可以用 {@code ~/.craftgraph/profiles/*.json}
 * 覆盖同名 {@code typeId}——新模组只加数据、不重新编译。
 *
 * <p>加载失败只影响对应文件（见 {@link ProfileLoader}），不会让桥接不可用。
 */
public final class Profiles {

    private static final Logger LOGGER = LoggerFactory.getLogger(Profiles.class);

    private static volatile Map<String, RecipeProfile> cache;

    private Profiles() {
    }

    /** 没有对应的 profile 时返回 {@code null}（调用方应当让适配器不生效）。 */
    public static RecipeProfile forType(String typeId) {
        return all().get(typeId);
    }

    public static Map<String, RecipeProfile> all() {
        Map<String, RecipeProfile> current = cache;
        if (current == null) {
            current = ProfileLoader.load(userDir(), Profiles.class.getClassLoader());
            cache = current;
            LOGGER.info("CraftGraph: loaded {} recipe profile(s): {}",
                    current.size(), String.join(", ", current.keySet()));
        }
        return current;
    }

    /** 测试用：清掉缓存，让下一次加载重新读文件。 */
    static void reload() {
        cache = null;
    }

    private static Path userDir() {
        return Path.of(System.getProperty("user.home", "."), ".craftgraph", "profiles");
    }
}
