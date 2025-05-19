package dumb.cogthought.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dumb.cogthought.Persistence;
import dumb.cogthought.util.Log;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static dumb.cogthought.util.Log.error;
import static dumb.cogthought.util.Log.info;
import static dumb.cogthought.util.Log.message;

public class FilePersistence implements Persistence {

    private final Path baseDir;
    private final ObjectMapper mapper;

    public FilePersistence(String baseDirPath) {
        this.baseDir = requireNonNull(Paths.get(baseDirPath));
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            error("Failed to create persistence directory: " + baseDir + ": " + e.getMessage());
            throw new RuntimeException("Failed to initialize FilePersistence", e);
        }
        info("Initialized FilePersistence at: " + baseDir.toAbsolutePath());
    }

    private Path resolvePath(String key) {
        if (key.contains("..") || key.startsWith("/") || key.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
        return baseDir.resolve(key);
    }

    @Override
    public <T> void save(String key, T data) {
        Path filePath = resolvePath(key);
        try {
            Files.createDirectories(filePath.getParent());
            mapper.writeValue(filePath.toFile(), data);
        } catch (IOException e) {
            error("Failed to save data for key: " + key + ": " + e.getMessage());
            throw new RuntimeException("Persistence save failed", e);
        }
    }

    @Override
    public <T> Optional<T> load(String key, Class<T> type) {
        Path filePath = resolvePath(key);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(filePath.toFile(), type));
        } catch (IOException e) {
            error("Failed to load data for key: " + key + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        Path filePath = resolvePath(key);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            error("Failed to delete data for key: " + key + ": " + e.getMessage());
            throw new RuntimeException("Persistence delete failed", e);
        }
    }

    @Override
    public Stream<String> listKeys() {
        try {
            return Files.walk(baseDir)
                    .filter(Files::isRegularFile)
                    .map(baseDir::relativize)
                    .map(Path::toString);
        } catch (IOException e) {
            error("Failed to list keys: " + e.getMessage());
            throw new RuntimeException("Persistence listKeys failed", e);
        }
    }

    @Override
    public Stream<String> listKeysByPrefix(String prefix) {
        try {
            Path prefixPath = baseDir.resolve(prefix).normalize();
            if (!prefixPath.startsWith(baseDir)) {
                 return Stream.empty();
            }

            return Files.walk(prefixPath)
                    .filter(Files::isRegularFile)
                    .map(baseDir::relativize)
                    .map(Path::toString)
                    .filter(key -> key.startsWith(prefix));
        } catch (IOException e) {
            error("Failed to list keys with prefix " + prefix + ": " + e.getMessage());
            throw new RuntimeException("Persistence listKeysByPrefix failed", e);
        }
    }

    @Override
    public void clear() {
        try {
            Files.walk(baseDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            error("Failed to delete path during clear: " + path + ": " + e.getMessage());
                        }
                    });
            message("Persistence directory cleared: " + baseDir.toAbsolutePath());
        } catch (IOException e) {
            error("Failed to clear persistence directory: " + baseDir + ": " + e.getMessage());
            throw new RuntimeException("Persistence clear failed", e);
        }
    }
}
