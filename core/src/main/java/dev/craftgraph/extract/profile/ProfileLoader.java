package dev.craftgraph.extract.profile;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 加载 recipe profile：jar 内资源 + 用户覆盖目录。
 *
 * <h2>位置</h2>
 *
 * <ul>
 *   <li>内置：classpath 下 {@code dev/craftgraph/profiles/}，由 {@code index.json} 列出文件名。</li>
 *   <li>覆盖：{@code ~/.craftgraph/profiles/*.json}，同 {@code typeId} 时用户版本优先。</li>
 * </ul>
 *
 * <h2>坏 profile 必须响</h2>
 *
 * 单个文件解析失败只跳过它并打日志 —— 不能让一份写错的 profile 把整条桥接带崩；
 * 但**不能静默**：日志里会带文件名和原因。缺字段、未知角色都算错误，
 * 因为「以为读懂了、其实字段名没对上」会产出一份看起来完整的错误原料表。
 *
 * <p>纯逻辑，只依赖 Gson，可单测。
 */
public final class ProfileLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileLoader.class);

    /** classpath 里的 profile 目录。 */
    public static final String RESOURCE_ROOT = "dev/craftgraph/profiles/";

    private static final Gson GSON = new Gson();

    private ProfileLoader() {
    }

    /**
     * 加载全部 profile。
     *
     * @param userDir 用户覆盖目录，可为 {@code null}
     * @param loader  读取内置资源的 ClassLoader
     * @return typeId → profile；解析失败的文件会被跳过并记录日志
     */
    public static Map<String, RecipeProfile> load(Path userDir, ClassLoader loader) {
        Map<String, RecipeProfile> out = new LinkedHashMap<>();

        for (String name : index(loader)) {
            try (InputStream in = loader.getResourceAsStream(RESOURCE_ROOT + name)) {
                if (in == null) {
                    LOGGER.warn("CraftGraph: recipe profile resource is missing: {}", RESOURCE_ROOT + name);
                    continue;
                }
                RecipeProfile profile = parse(new InputStreamReader(in, StandardCharsets.UTF_8), name);
                out.put(profile.typeId(), profile);
            } catch (Throwable t) {
                LOGGER.warn("CraftGraph: cannot load recipe profile {}: {}", name, t.toString());
            }
        }

        if (userDir != null && Files.isDirectory(userDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(userDir, "*.json")) {
                for (Path file : stream) {
                    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        RecipeProfile profile = parse(reader, file.getFileName().toString());
                        out.put(profile.typeId(), profile);
                    } catch (Throwable t) {
                        LOGGER.warn("CraftGraph: cannot load recipe profile {}: {}", file, t.toString());
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("CraftGraph: cannot scan recipe profile dir {}: {}", userDir, e.toString());
            }
        }

        return Map.copyOf(out);
    }

    /**
     * 解析单个 profile。
     *
     * @throws IllegalArgumentException 缺字段、未知角色、JSON 结构不对
     */
    public static RecipeProfile parse(Reader reader, String source) {
        ProfileJson json = GSON.fromJson(reader, ProfileJson.class);
        require(json != null, source, "empty profile");
        require(nonBlank(json.typeId()), source, "typeId is required");
        require(nonBlank(json.machineField()), source, "machineField is required");
        require(nonBlank(json.durationField()), source, "durationField is required");
        require(nonBlank(json.requirementsField()), source, "requirementsField is required");
        require(nonBlank(json.typeField()), source, "typeField is required");
        require(nonBlank(json.modeField()), source, "modeField is required");
        require(nonBlank(json.itemField()), source, "itemField is required");
        require(nonBlank(json.fluidField()), source, "fluidField is required");
        require(nonBlank(json.countField()), source, "countField is required");
        require(nonBlank(json.chanceField()), source, "chanceField is required");
        require(json.roles() != null && !json.roles().isEmpty(), source, "roles must not be empty");

        Map<String, RequirementRole> roles = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : json.roles().entrySet()) {
            require(nonBlank(entry.getKey()), source, "role key must not be blank");
            roles.put(entry.getKey(), role(entry.getValue(), source));
        }

        return new RecipeProfile(
                json.typeId(), json.machineField(), json.durationField(), json.requirementsField(),
                json.typeField(), json.modeField(), json.itemField(), json.fluidField(),
                json.countField(), json.chanceField(), roles);
    }

    // ---------------------------------------------------------------- 内部

    private static List<String> index(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE_ROOT + "index.json")) {
            if (in == null) {
                LOGGER.warn("CraftGraph: recipe profile index is missing: {}", RESOURCE_ROOT + "index.json");
                return List.of();
            }
            ProfileIndex index = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ProfileIndex.class);
            if (index == null || index.profiles() == null) return List.of();
            return new ArrayList<>(index.profiles());
        } catch (Throwable t) {
            LOGGER.warn("CraftGraph: cannot read recipe profile index: {}", t.toString());
            return List.of();
        }
    }

    private static RequirementRole role(String value, String source) {
        require(nonBlank(value), source, "role value must not be blank");
        try {
            return RequirementRole.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("profile " + source + ": unknown role '" + value + "'");
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String source, String message) {
        if (!condition) throw new IllegalArgumentException("profile " + source + ": " + message);
    }

    // Gson 的映射目标：字段名与 profile JSON 一一对应
    private record ProfileIndex(List<String> profiles) {
    }

    private record ProfileJson(
            String typeId,
            String machineField,
            String durationField,
            String requirementsField,
            String typeField,
            String modeField,
            String itemField,
            String fluidField,
            String countField,
            String chanceField,
            Map<String, String> roles) {
    }
}
