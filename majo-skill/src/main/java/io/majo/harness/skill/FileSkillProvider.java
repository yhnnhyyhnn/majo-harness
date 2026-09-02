package io.majo.harness.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Local {@link SkillProvider}: each subdirectory of {@code root} holding a
 * {@code SKILL.md} is one skill. The file may carry YAML front matter
 * ({@code ---}…{@code ---}) whose {@code description} keys the catalog; the
 * remaining body is the instructions. A configured root that is not a
 * directory fails loudly (misconfiguration never silently yields no skills).
 */
public final class FileSkillProvider implements SkillProvider {

    public static final String SKILL_FILE = "SKILL.md";

    private final Path root;

    public FileSkillProvider(Path root) {
        if (!Files.isDirectory(root)) {
            throw new SkillException("skill-files: root is not a directory: " + root);
        }
        this.root = root;
    }

    @Override
    public List<Skill> skills() {
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.filter(Files::isDirectory).sorted().toList()) {
                Path skillFile = entry.resolve(SKILL_FILE);
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                skills.add(read(entry, skillFile));
            }
            return List.copyOf(skills);
        } catch (IOException e) {
            throw new SkillException("skill-files: cannot list " + root + ": " + e.getMessage(), e);
        }
    }

    private static Skill read(Path directory, Path skillFile) {
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            FrontMatter frontMatter = FrontMatter.parse(content);
            return new Skill(directory.getFileName().toString(),
                    frontMatter.description(), frontMatter.body());
        } catch (IOException e) {
            throw new SkillException("skill-files: cannot read " + skillFile + ": " + e.getMessage(), e);
        }
    }

    /** Minimal YAML front-matter reader (description only; tolerant). */
    record FrontMatter(String description, String body) {

        static FrontMatter parse(String content) {
            content = content.replace("\r\n", "\n"); // tolerate CRLF skill files
            if (content.startsWith("---\n")) {
                int end = content.indexOf("\n---", 4);
                if (end > 0) {
                    String header = content.substring(4, end);
                    String body = content.substring(end + 4).stripLeading();
                    String description = null;
                    for (String line : header.split("\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0 && line.substring(0, colon).trim().equals("description")) {
                            description = line.substring(colon + 1).trim();
                            break;
                        }
                    }
                    return new FrontMatter(description, body);
                }
            }
            return new FrontMatter(null, content);
        }
    }
}
